// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {
    @TempDir
    Path dir;

    /** Feeds the lines to a server, waits for it to finish (end of input), returns the responses by id. */
    private Map<String, JsonObject> session(String... lines) throws Exception {
        var input = new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        new McpServer(input, output, dir.resolve("runs"), dir).serve();
        var responses = new HashMap<String, JsonObject>();
        var text = output.toString(StandardCharsets.US_ASCII);
        for (var line : text.split("\n")) {
            if (line.isBlank()) continue;
            for (var i = 0; i < line.length(); i++) assertTrue(line.charAt(i) < 0x80, "non-ASCII output");
            var message = JsonParser.parseString(line).getAsJsonObject();
            responses.put(message.get("id").toString(), message);
        }
        return responses;
    }

    private static String call(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}";
    }

    private static String text(JsonObject response) {
        return response.getAsJsonObject("result").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static boolean isError(JsonObject response) {
        return response.getAsJsonObject("result").get("isError").getAsBoolean();
    }

    @Test
    void handshakeAndToolList() throws Exception {
        var r = session(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}",
            "not json",
            call(5, "no_such_tool", "{}"),
            call(6, "cct_guide", "{}"),
            call(9, "cct_guide", "{\"section\": 7}"),
            call(10, "cct_guide", "{\"section\": 42}"),
            "{\"jsonrpc\":\"2.0\",\"id\":\"s\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":{\"not\":\"a string\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}");

        var init = r.get("1").getAsJsonObject("result");
        assertEquals("2025-06-18", init.get("protocolVersion").getAsString());
        assertEquals("cct-run", init.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(init.getAsJsonObject("capabilities").has("tools"));
        assertTrue(init.get("instructions").getAsString().contains("cct_guide"));
        assertEquals(McpServer.PROTOCOL_VERSIONS.get(0), r.get("\"s\"").getAsJsonObject("result").get("protocolVersion").getAsString());

        var tools = r.get("2").getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(18, tools.size());
        for (var tool : tools) assertEquals("object", tool.getAsJsonObject().getAsJsonObject("inputSchema").get("type").getAsString());

        assertTrue(r.get("3").has("result"));
        assertEquals(-32601, r.get("4").getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32700, r.get("null").getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, r.get("5").getAsJsonObject("error").get("code").getAsInt());
        assertFalse(isError(r.get("6")));
        assertTrue(text(r.get("6")).contains("cct-run agent guide"));
        assertTrue(text(r.get("9")).startsWith("## 7. Real computers in the game"), text(r.get("9")));
        assertFalse(text(r.get("9")).contains("## 6."));
        assertTrue(isError(r.get("10")));
        assertTrue(text(r.get("10")).contains("## 1. Workflow"));
        assertEquals(-32600, r.get("7").getAsJsonObject("error").get("code").getAsInt());
        assertTrue(r.get("8").has("result"), "still serving after a malformed message");
    }

    @Test
    void runsCodeAndFilesInChildProcesses() throws Exception {
        var program = dir.resolve("my prog.lua");
        Files.writeString(program, "print('from file', ...)");
        var r = session(
            call(1, "cct_run", "{\"code\":\"print('hi \\u00e9')\\nlocal x = nil\\nprint(x.y)\",\"name\":\"bad.lua\"}"),
            call(2, "cct_run", "{\"file\":" + JsonParser.parseString("\"" + program.toString().replace("\\", "\\\\") + "\"") + ",\"args\":[\"a\",\"b c\"]}"),
            call(3, "cct_run", "{\"code\":\"print(peripheral.getType('left'))\",\"fixture\":{\"schema\":\"cct-run/fixture@1\",\"peripherals\":{\"left\":{\"type\":\"monitor\"}}}}"),
            call(4, "cct_run", "{\"code\":\"print(1)\",\"fixture\":{\"schema\":\"cct-run/fixture@1\",\"peripherals\":{\"left\":{\"type\":\"toaster\"}}}}"),
            call(5, "cct_run", "{\"file\":\"x.lua\",\"code\":\"print(1)\"}"),
            call(6, "cct_run", "{\"code\":\"print(1)\",\"name\":\"../evil.lua\"}"),
            call(7, "cct_run", "{\"code\":\"print(1)\",\"bogus\":1}"),
            call(8, "cct_run", "{\"file\":\"missing.lua\"}"));

        var error = JsonParser.parseString(text(r.get("1"))).getAsJsonObject();
        assertFalse(isError(r.get("1")), "a Lua error is a run result, not a tool error");
        assertEquals("error", error.get("status").getAsString());
        assertEquals("/bad.lua:3: attempt to index local 'x' (a nil value)", error.getAsJsonObject("error").get("message").getAsString());
        assertTrue(Files.isDirectory(Path.of(error.get("run_dir").getAsString())));

        var fromFile = JsonParser.parseString(text(r.get("2"))).getAsJsonObject();
        assertEquals("ok", fromFile.get("status").getAsString());
        assertEquals("from file\ta\tb c", fromFile.getAsJsonObject("screen").getAsJsonArray("lines").get(0).getAsString());

        var withFixture = JsonParser.parseString(text(r.get("3"))).getAsJsonObject();
        assertEquals("monitor", withFixture.getAsJsonObject("screen").getAsJsonArray("lines").get(0).getAsString());

        assertTrue(isError(r.get("4")));
        assertTrue(text(r.get("4")).contains("(inline fixture)"), text(r.get("4")));
        assertTrue(text(r.get("4")).contains("$.peripherals.left.type"), text(r.get("4")));
        assertFalse(text(r.get("4")).contains("cct-run-mcp-"), "temp paths are hidden");

        assertTrue(isError(r.get("5")));
        assertTrue(isError(r.get("6")));
        assertTrue(text(r.get("7")).contains("unknown argument 'bogus'"));
        assertTrue(text(r.get("8")).contains("program not found"), text(r.get("8")));

        // Read-only tools on the runs above (a new session, so the runs have finished).
        var q = session(
            call(1, "cct_trace", "{\"kind\":[\"program_end\"]}"),
            call(2, "cct_screen", "{}"),
            call(3, "cct_trace", "{\"run\":" + JsonParser.parseString("\"" + error.get("run_dir").getAsString().replace("\\", "\\\\") + "\"") + ",\"kind\":\"program_end\",\"limit\":1}"),
            call(4, "cct_trace", "{\"kind\":[\"modem_transmit\"]}"),
            call(5, "cct_screen", "{\"source\":\"monitor_9\"}"),
            call(6, "cct_trace", "{\"run\":\"" + dir.toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(isError(q.get("1")));
        assertTrue(text(q.get("1")).contains("\"kind\":\"program_end\""));
        assertTrue(text(q.get("2")).contains("\"lines\""));
        assertTrue(text(q.get("3")).contains("attempt to index local"));
        assertTrue(text(q.get("3")).contains("stopped at limit 1"));
        assertEquals("(no matching trace records)", text(q.get("4")));
        assertTrue(isError(q.get("5")));
        assertTrue(isError(q.get("6")));
        assertTrue(text(q.get("6")).contains("not a run directory"));
    }

    @Test
    void cancelStopsARunningChild() throws Exception {
        var toServer = new java.io.PipedOutputStream();
        var input = new java.io.PipedInputStream(toServer);
        var output = new ByteArrayOutputStream();
        var server = new Thread(() -> {
            try {
                new McpServer(input, output, dir.resolve("runs"), dir).serve();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        server.start();
        var start = System.nanoTime();
        toServer.write((call(1, "cct_run", "{\"code\":\"while true do sleep(1) end\",\"timeout\":60}") + "\n").getBytes(StandardCharsets.UTF_8));
        toServer.flush();
        Thread.sleep(2000); // the child JVM is running by now
        toServer.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}\n".getBytes(StandardCharsets.UTF_8));
        toServer.close();
        server.join(30_000);
        assertFalse(server.isAlive(), "server finished after the cancelled run");
        assertTrue(System.nanoTime() - start < 30_000_000_000L, "the child was stopped long before its 60 s timeout");
        assertEquals("", output.toString(StandardCharsets.US_ASCII), "no response for a cancelled request");
    }
}

// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cct_live_* tools against a fake cct-live endpoint. */
class LiveToolsTest {
    @TempDir
    Path dir;

    private HttpServer fake;
    /** "METHOD path?query body" of every request the fake endpoint received. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    /** The status reply up to (not including) its closing brace; tests shorten it to model an older endpoint. */
    private volatile String statusJson = "{\"last_seq\":5,\"lines\":[\"hello\",\"Ready.\"],\"screen_hash\":\"ab\"";
    /** Milliseconds the fake endpoint takes to answer /status (0 = immediately). */
    private volatile long statusDelayMillis;
    private volatile java.util.concurrent.CountDownLatch statusStarted;
    private volatile int statusCode = 200;

    private Path game() throws Exception {
        fake = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        fake.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var uri = exchange.getRequestURI();
            requests.add(exchange.getRequestMethod() + " " + uri.getPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) + (body.isEmpty() ? "" : " " + body));
            int status;
            String reply;
            if (!"Bearer secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                reply = "{\"error\":\"missing or wrong token\"}";
            } else if (uri.getPath().endsWith("/status")) {
                if (statusStarted != null) statusStarted.countDown();
                if (statusDelayMillis > 0) {
                    try {
                        Thread.sleep(statusDelayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                status = statusCode;
                var match = uri.getRawQuery() != null && uri.getRawQuery().contains("kinds=printed_error")
                    ? ",\"match\":{\"seq\":6,\"kind\":\"printed_error\",\"text\":\"boom\"}" : "";
                reply = statusJson + match + "}";
            } else if (uri.getPath().endsWith("/files") && uri.getRawQuery() != null && uri.getRawQuery().startsWith("op=stat")) {
                status = 200;
                reply = "{\"exists\":false}";
            } else if (uri.getPath().equals("/computers/9/screen")) {
                status = 404;
                reply = "{\"error\":\"no computer with id 9 is loaded\"}";
            } else {
                status = 200;
                reply = "{\"ok\":true,\"path\":\"" + uri.getPath() + "\"}";
            }
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        var game = dir.resolve("game");
        Files.createDirectories(game.resolve("cct-live"));
        Files.writeString(game.resolve("cct-live/token.txt"), "secret\n");
        Files.writeString(game.resolve("cct-live/endpoint.json"),
            "{\"schema\":\"cct-live/endpoint@0\",\"url\":\"http://127.0.0.1:" + fake.getAddress().getPort() + "/\"}");
        return game;
    }

    @AfterEach
    void stop() {
        if (fake != null) fake.stop(0);
    }

    @Test
    void clientBridgeDiscoveryUsesItsOwnTokenAndNeverFallsBackAfterDisconnect() throws Exception {
        var game = game();
        var home = game.resolve("cct-live");
        Files.createDirectories(home.resolve("client"));
        Files.copy(home.resolve("endpoint.json"), home.resolve("client/endpoint.json"));
        Files.copy(home.resolve("token.txt"), home.resolve("client/token.txt"));
        Files.writeString(home.resolve("token.txt"), "server-admin-token-must-not-be-used");
        var live = new LiveTools(game, dir);
        var result = live.call(LiveTools.COMPUTERS, new JsonObject());
        assertFalse(result.has("isError") && result.get("isError").getAsBoolean());
        Files.delete(home.resolve("client/endpoint.json"));
        var error = org.junit.jupiter.api.Assertions.assertThrows(McpTools.ToolError.class,
            () -> live.call(LiveTools.COMPUTERS, new JsonObject()));
        assertTrue(error.getMessage().contains("no active endpoint.json"));
        assertEquals(1, requests.size());
    }

    @Test
    void unavailableActionBaselineNeverAcceptsStaleText() throws Exception {
        var game = game();
        var live = new LiveTools(game, dir);
        statusJson = "{\"last_seq\":5,\"lines\":[\"Ready.\"]"; // baseline cannot be captured
        live.call(LiveTools.TYPE, JsonParser.parseString("{\"computer\":0,\"text\":\"noop\"}").getAsJsonObject());
        statusJson = "{\"last_seq\":5,\"lines\":[\"Ready.\"],\"screen_hash\":\"ab\"";
        var answer = live.call(LiveTools.WAIT, JsonParser.parseString("{\"computer\":0,\"text\":\"Ready.\",\"timeout\":1}").getAsJsonObject());
        assertTrue(text(answer).contains("timeout"));
        assertTrue(text(answer).contains("baseline_warning"));
    }
    @Test void longSequenceArgumentsReachTheEndpointWithoutIntTruncation() throws Exception {
        var game=game();var live=new LiveTools(game,dir);
        live.call(LiveTools.STATUS,JsonParser.parseString("{\"computer\":0,\"after\":9223372036854775807}").getAsJsonObject());
        assertTrue(requests.stream().anyMatch(r->r.contains("after=9223372036854775807")));
    }

    @Test
    void cancellingLiveWaitInterruptsPollAndKeepsServerResponsive() throws Exception {
        var game = game();
        statusStarted = new java.util.concurrent.CountDownLatch(1);
        statusDelayMillis = 1000;
        var input = new java.io.PipedInputStream();
        var writer = new java.io.PipedOutputStream(input);
        var output = new ByteArrayOutputStream();
        var serving = new java.util.concurrent.FutureTask<Integer>(() -> new McpServer(input, output, dir.resolve("runs"), dir, game).serve());
        var thread = new Thread(serving);
        thread.start();
        writer.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"cct_live_wait\",\"arguments\":{\"computer\":0,\"text\":\"absent\",\"timeout\":60}}}\n").getBytes(StandardCharsets.UTF_8));
        writer.flush();
        assertTrue(statusStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
        writer.write(("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n").getBytes(StandardCharsets.UTF_8));
        writer.close();
        assertEquals(0, serving.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"id\":2"));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("\"id\":1"));
        assertEquals(1,requests.size(),"the cancelled wait made no further HTTP calls");
    }
    @Test void endpointFailureAndMalformedDiscoveryAreClearAndRecoverable() throws Exception {
        var game=game();var live=new LiveTools(game,dir);
        statusCode=500;
        var args=JsonParser.parseString("{\"computer\":0}").getAsJsonObject();
        var error=org.junit.jupiter.api.Assertions.assertThrows(McpTools.ToolError.class,()->live.call(LiveTools.STATUS,args));
        assertTrue(error.getMessage().contains("HTTP 500"));
        statusCode=200;assertFalse(live.call(LiveTools.STATUS,args).get("isError").getAsBoolean());
        Files.writeString(game.resolve("cct-live/endpoint.json"),"{\"url\":[]}");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(McpTools.ToolError.class,()->live.call(LiveTools.STATUS,args)).getMessage().contains("cannot read"));
    }

    private Map<String, JsonObject> session(Path liveDir, String... calls) throws Exception {
        var lines = new ArrayList<String>();
        for (var i = 0; i < calls.length; i++) {
            var split = calls[i].indexOf(' ');
            lines.add("{\"jsonrpc\":\"2.0\",\"id\":" + (i + 1) + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + calls[i].substring(0, split) + "\",\"arguments\":" + calls[i].substring(split + 1) + "}}");
        }
        var input = new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        var output = new ByteArrayOutputStream();
        new McpServer(input, output, dir.resolve("runs"), dir, liveDir).serve();
        var responses = new HashMap<String, JsonObject>();
        for (var line : output.toString(StandardCharsets.US_ASCII).split("\n")) {
            if (line.isBlank()) continue;
            var message = JsonParser.parseString(line).getAsJsonObject();
            responses.put(message.get("id").toString(), message.getAsJsonObject("result"));
        }
        return responses;
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void callsTheEndpoint() throws Exception {
        var r = session(game(),
            "cct_live_computers {}",
            "cct_live_screen {\"computer\": 0}",
            "cct_live_trace {\"computer\": 0, \"kind\": [\"api\", \"handle\"], \"after\": 12, \"ticks\": \"40-\", \"limit\": 5}",
            "cct_live_event {\"computer\": 1, \"name\": \"char\", \"args\": [\"y\"]}",
            "cct_live_type {\"computer\": 1, \"text\": \"ls rom\"}",
            "cct_live_power {\"computer\": 1, \"action\": \"reboot\"}",
            "cct_live_attach {\"computer\": 1}",
            "cct_live_detach {\"computer\": 1}",
            "cct_live_screen {\"computer\": 2, \"regions\": true, \"tick\": 300}",
            "cct_live_click {\"computer\": 3, \"x\": 12, \"y\": 16}",
            "cct_live_files {\"computer\": 4, \"op\": \"read\", \"path\": \"icons/User.lua\", \"length\": 100}",
            "cct_live_click {\"computer\": 5, \"source\": \"right\", \"x\": 3, \"y\": 2}",
            "cct_live_files {\"computer\": 5, \"op\": \"write\", \"path\": \"a.lua\", \"text\": \"print(1)\"}",
            "cct_live_attach {\"computer\": 5, \"lease_seconds\": 3600}",
            "cct_live_type {\"computer\": 6, \"text\": \"a\\nb\", \"enter\": false}",
            "cct_live_trace {\"computer\": 7, \"summary\": true}",
            "cct_live_status {\"computer\": 7, \"source\": \"right\", \"after\": 3}",
            "cct_live_body {\"computer\": 7, \"body\": 2, \"length\": 10}");
        for (var i = 1; i <= 18; i++) assertFalse(r.get(Integer.toString(i)).get("isError").getAsBoolean(), text(r.get(Integer.toString(i))));
        assertTrue(text(r.get("1")).contains("/computers"));
        // Calls run concurrently, so compare as a set; the three requests of cct_live_type are sent in order.
        var expected = List.of(
            "GET /computers",
            "GET /computers/0/screen",
            "GET /computers/0/trace?kind=api%2Chandle&after=12&limit=5&ticks=40-",
            "POST /computers/1/event {\"name\":\"char\",\"args\":[\"y\"]}",
            "POST /computers/1/event {\"name\":\"paste\",\"args\":[\"ls rom\"]}",
            "POST /computers/1/event {\"name\":\"key\",\"args\":[257,false]}",
            "POST /computers/1/event {\"name\":\"key_up\",\"args\":[257]}",
            "POST /computers/1/power {\"action\":\"reboot\"}",
            "POST /computers/1/attach {}",
            "GET /computers/1/files?op=stat&path=.cctlive",
            "POST /computers/1/detach {}",
            "GET /computers/2/screen?regions=1&tick=300",
            "POST /computers/3/event {\"name\":\"mouse_click\",\"args\":[1,12,16]}",
            "POST /computers/3/event {\"name\":\"mouse_up\",\"args\":[1,12,16]}",
            "GET /computers/4/files?op=read&path=icons%2FUser.lua&length=100",
            "POST /computers/5/event {\"name\":\"monitor_touch\",\"args\":[\"right\",3,2]}",
            "POST /computers/5/files {\"path\":\"a.lua\",\"text\":\"print(1)\",\"append\":false}",
            "POST /computers/5/attach {\"lease_seconds\":3600}",
            "POST /computers/6/event {\"name\":\"paste\",\"args\":[\"a\"]}",
            "POST /computers/6/event {\"name\":\"key\",\"args\":[257,false]}",
            "POST /computers/6/event {\"name\":\"key_up\",\"args\":[257]}",
            "POST /computers/6/event {\"name\":\"paste\",\"args\":[\"b\"]}",
            "GET /computers/7/trace?exclude=screen&limit=50&summary=1",
            "GET /computers/7/status?source=right&after=3",
            "GET /computers/7/body?n=2&length=10",
            // Screen hashes before each input action (cct_live_wait ignores text on the unchanged screen).
            "GET /computers/1/status", "GET /computers/1/status", "GET /computers/1/status",
            "GET /computers/3/status", "GET /computers/5/status", "GET /computers/6/status");
        assertEquals(expected.stream().sorted().toList(), requests.stream().sorted().toList());
        var typed = requests.stream().filter(q -> q.startsWith("POST /computers/1/event") && (q.contains("paste") || q.contains("\"key"))).toList();
        assertEquals(List.of(expected.get(4), expected.get(5), expected.get(6)), typed);
        var multi = requests.stream().filter(q -> q.startsWith("POST /computers/6/")).toList();
        assertEquals(expected.stream().filter(q -> q.startsWith("POST /computers/6/")).toList(), multi);
    }

    @Test
    void waitsOnTheServerSide() throws Exception {
        var game = game();
        var r = session(game,
            "cct_live_wait {\"computer\": 0, \"text\": \"Ready\"}",
            "cct_live_wait {\"computer\": 0, \"kind\": [\"printed_error\"]}",
            "cct_live_wait {\"computer\": 0, \"text\": \"never\", \"timeout\": 1}",
            "cct_live_wait {\"computer\": 0, \"idle_seconds\": 0.5, \"timeout\": 5}",
            "cct_live_wait {\"computer\": 0}");
        assertTrue(text(r.get("1")).contains("\"met\":\"text\""), text(r.get("1")));
        assertTrue(text(r.get("2")).contains("\"met\":\"kind\"") && text(r.get("2")).contains("boom"), text(r.get("2")));
        assertTrue(text(r.get("3")).contains("\"met\":\"timeout\""), text(r.get("3")));
        assertTrue(text(r.get("4")).contains("\"met\":\"idle\""), text(r.get("4")));
        assertTrue(r.get("5").get("isError").getAsBoolean());
    }

    @Test
    void waitForAKindStartsAfterTheCurrentRecords() throws Exception {
        session(game(), "cct_live_wait {\"computer\": 0, \"kind\": [\"printed_error\"]}");
        // Without 'after' the first status (no kinds) fixes the starting seq, so an old record cannot match.
        assertEquals("GET /computers/0/status", requests.get(0));
        assertEquals("GET /computers/0/status?after=5&kinds=printed_error", requests.get(1));
    }

    /** Field test 4: after a reboot, waiting for ">" matched the old prompt at once and the program never restarted. */
    @Test
    void waitIgnoresTextLeftFromBeforeTheAction() throws Exception {
        // One LiveTools, calls in order (an MCP session runs calls concurrently).
        var tools = new LiveTools(game(), dir);
        java.util.function.Function<String, String> call = json -> {
            try {
                var a = JsonParser.parseString(json).getAsJsonObject();
                var name = a.remove("tool").getAsString();
                return text(tools.call(name, a));
            } catch (McpTools.ToolError e) {
                throw new AssertionError(e);
            }
        };
        call.apply("{\"tool\": \"cct_live_type\", \"computer\": 0, \"text\": \"factory\"}");
        // The fake screen never changes: its text is what was there before the typing.
        var stale = call.apply("{\"tool\": \"cct_live_wait\", \"computer\": 0, \"text\": \"Ready\", \"timeout\": 1}");
        assertTrue(stale.contains("\"met\":\"timeout\""), stale);
        assertTrue(stale.contains("has not changed since your last action"), stale);
        var accepted = call.apply("{\"tool\": \"cct_live_wait\", \"computer\": 0, \"text\": \"Ready\", \"unchanged\": true, \"timeout\": 1}");
        assertTrue(accepted.contains("\"met\":\"text\""), accepted);
        var other = call.apply("{\"tool\": \"cct_live_wait\", \"computer\": 1, \"text\": \"Ready\", \"timeout\": 1}");
        assertTrue(other.contains("\"met\":\"text\""), "no action on computer 1, so its screen counts: " + other);
    }

    @Test
    void snapshotsAndTraceFilters() throws Exception {
        var r = session(game(),
            "cct_live_snapshot {\"computer\": 2, \"op\": \"save\"}",
            "cct_live_snapshot {\"computer\": 2, \"op\": \"restore\", \"name\": \"clean-1\"}",
            "cct_live_snapshot {\"computer\": 2, \"op\": \"undo\"}",
            "cct_live_trace {\"computer\": 2, \"kind\": [\"handle\"], \"contains\": \"User.lua\"}",
            "cct_live_trace {\"computer\": 2, \"peripheral\": \"Create_Stressometer_0\", \"method\": \"getStress\"}",
            "cct_live_peripheral {\"computer\": 2, \"name\": \"Create_Stressometer_0\", \"method\": \"getStress\"}",
            "cct_live_peripheral {\"computer\": 2, \"name\": \"left\", \"method\": \"getItemDetail\", \"args\": [1]}",
            "cct_live_peripheral {\"computer\": 2}");
        assertFalse(r.get("1").get("isError").getAsBoolean());
        assertFalse(r.get("2").get("isError").getAsBoolean());
        assertTrue(r.get("3").get("isError").getAsBoolean());
        assertFalse(r.get("4").get("isError").getAsBoolean());
        assertFalse(r.get("5").get("isError").getAsBoolean());
        assertFalse(r.get("6").get("isError").getAsBoolean());
        assertTrue(r.get("8").get("isError").getAsBoolean());
        assertEquals(List.of(
                "GET /computers/2/trace?exclude=screen&limit=50&peripheral=Create_Stressometer_0&method=getStress",
                "GET /computers/2/trace?kind=handle&limit=50&contains=User.lua",
                "POST /computers/2/peripheral {\"name\":\"Create_Stressometer_0\",\"method\":\"getStress\"}",
                "POST /computers/2/peripheral {\"name\":\"left\",\"method\":\"getItemDetail\",\"args\":[1]}",
                "POST /computers/2/snapshot {\"op\":\"restore\",\"name\":\"clean-1\"}",
                "POST /computers/2/snapshot {\"op\":\"save\"}"),
            requests.stream().sorted().toList());
    }

    @Test
    void luaTimeoutStaysPendingAndCanBeCollected() throws Exception {
        var r = session(game(),
            "cct_live_lua {\"computer\": 0, \"result\": \"lua_1_2\", \"timeout\": 1}",
            "cct_live_lua {\"computer\": 0, \"result\": \"../x\"}",
            "cct_live_lua {\"computer\": 0}");
        assertFalse(r.get("1").get("isError").getAsBoolean(), text(r.get("1")));
        assertTrue(text(r.get("1")).contains("\"pending\":\"lua_1_2\""), text(r.get("1")));
        assertTrue(r.get("2").get("isError").getAsBoolean());
        assertTrue(r.get("3").get("isError").getAsBoolean());
        assertTrue(requests.stream().allMatch(q -> q.startsWith("GET /computers/0/files?op=stat&path=.cctlive%2Flua_1_2.out")), requests.toString());
    }

    @Test
    void luaNeedsTheShellPromptAndQuotesTheCode() throws Exception {
        var r = session(game(), "cct_live_lua {\"computer\": 0, \"code\": \"return 1\"}");
        assertTrue(r.get("1").get("isError").getAsBoolean());
        assertTrue(text(r.get("1")).contains("shell prompt"), text(r.get("1")));
        assertTrue(requests.stream().noneMatch(q -> q.contains("/files")), "nothing written when the prompt is missing");

        var plain = LiveTools.wrapper("return 1", "/.cctlive/a.out", "/.cctlive/a.lua");
        assertTrue(plain.contains("load([[\nreturn 1\n]]"), plain);
        var nested = LiveTools.wrapper("local s = [[x]] .. [=[y]=]", "/o", "/s");
        assertTrue(nested.contains("load([==[\n"), nested);
    }

    @Test
    void explainsWhatIsMissing() throws Exception {
        var game = game();
        var r = session(null, "cct_live_computers {}");
        assertTrue(r.get("1").get("isError").getAsBoolean());
        assertTrue(text(r.get("1")).contains("--live"), text(r.get("1")));

        r = session(dir.resolve("nowhere"), "cct_live_computers {}");
        assertTrue(text(r.get("1")).contains("no active endpoint.json"), text(r.get("1")));

        r = session(game,
            "cct_live_screen {\"computer\": 9}",
            "cct_live_screen {}",
            "cct_live_wait {\"computer\": 1}",
            "cct_live_power {\"computer\": 1, \"action\": \"explode\"}",
            "cct_live_screen {\"computer\": 0, \"bogus\": 1}",
            "cct_live_computers {\"game_dir\": \"" + game.toString().replace("\\", "\\\\") + "\"}");
        assertEquals("cct-live: no computer with id 9 is loaded (HTTP 404)", text(r.get("1")));
        assertTrue(text(r.get("2")).contains("'computer'"));
        assertTrue(text(r.get("3")).contains("at least one of"));
        assertTrue(text(r.get("4")).contains("on, shutdown or reboot"));
        assertTrue(text(r.get("5")).contains("unknown argument 'bogus'"));
        assertFalse(r.get("6").get("isError").getAsBoolean());

        // Without a configured game directory, the last game_dir that worked is reused (calls in order).
        var tools = new LiveTools(null, dir);
        var withDir = new JsonObject();
        withDir.addProperty("game_dir", game.toString());
        assertFalse(tools.call(LiveTools.COMPUTERS, withDir).get("isError").getAsBoolean());
        var screen = new JsonObject();
        screen.addProperty("computer", 0);
        assertFalse(tools.call(LiveTools.SCREEN, screen).get("isError").getAsBoolean());
        for (var i = 1; i <= 5; i++) assertTrue(r.get(Integer.toString(i)).get("isError").getAsBoolean());

        Files.writeString(game.resolve("cct-live/token.txt"), "wrong\n");
        r = session(game, "cct_live_computers {}");
        assertEquals("cct-live: missing or wrong token (HTTP 401)", text(r.get("1")));

        fake.stop(0);
        Files.writeString(game.resolve("cct-live/token.txt"), "secret\n");
        r = session(game, "cct_live_computers {}");
        assertTrue(text(r.get("1")).contains("Is the world still open?"), text(r.get("1")));
    }

    /** A1: a status reply without screen_hash must not crash cct_live_wait (older cct-live / a gone monitor). */
    @Test
    void waitToleratesAStatusWithoutScreenHash() throws Exception {
        var tools = new LiveTools(game(), dir);
        // A click stores the baseline hash, so the old code NPE'd on the next status without one.
        tools.call("cct_live_click", JsonParser.parseString("{\"computer\":1,\"x\":1,\"y\":1}").getAsJsonObject());
        statusJson = "{\"last_seq\":5,\"lines\":[\"hello\"]";
        var result = tools.call("cct_live_wait", JsonParser.parseString(
            "{\"computer\":1,\"text\":\"never there\",\"idle_seconds\":0.5,\"timeout\":1}").getAsJsonObject());
        assertFalse(result.get("isError").getAsBoolean(), text(result));
        assertEquals("timeout", JsonParser.parseString(text(result)).getAsJsonObject().get("met").getAsString());
    }

    /** A2: a text match left over from before the action must not stop the idle condition from being checked. */
    @Test
    void waitUsesIdleEvenWhenTheTextIsLeftFromBefore() throws Exception {
        var tools = new LiveTools(game(), dir);
        tools.call("cct_live_click", JsonParser.parseString("{\"computer\":1,\"x\":1,\"y\":1}").getAsJsonObject());
        var start = System.nanoTime();
        var result = tools.call("cct_live_wait", JsonParser.parseString(
            "{\"computer\":1,\"text\":\"hello\",\"idle_seconds\":1,\"timeout\":4}").getAsJsonObject());
        var out = JsonParser.parseString(text(result)).getAsJsonObject();
        assertEquals("idle", out.get("met").getAsString(), text(result));
        // The old code waited for the whole timeout because the stale text match hid the idle branch.
        assertTrue(System.nanoTime() - start < 3_000_000_000L, "idle fired well before the 4 s timeout");
    }

    /** A3: a frozen endpoint must end the wait as "timeout" instead of blocking the tool for the HTTP timeout. */
    @Test
    void waitEndsWhenTheEndpointStopsAnswering() throws Exception {
        var tools = new LiveTools(game(), dir);
        statusDelayMillis = 5000;
        var start = System.nanoTime();
        var result = tools.call("cct_live_wait", JsonParser.parseString(
            "{\"computer\":1,\"text\":\"never\",\"unchanged\":true,\"timeout\":1}").getAsJsonObject());
        assertFalse(result.get("isError").getAsBoolean(), text(result));
        var out = JsonParser.parseString(text(result)).getAsJsonObject();
        assertEquals("timeout", out.get("met").getAsString(), text(result));
        assertTrue(out.get("note").getAsString().contains("did not answer"), text(result));
        assertTrue(System.nanoTime() - start < 2_500_000_000L, "bounded by the wait timeout, not 5 s or 15 s");
    }

    /** A10: a kind array with a non-string element is a bad argument, not a 500 from getAsString(). */
    @Test
    void waitRejectsNonStringKinds() throws Exception {
        var r = session(game(), "cct_live_wait {\"computer\":1,\"kind\":[1],\"timeout\":1}");
        assertTrue(r.get("1").get("isError").getAsBoolean(), text(r.get("1")));
        assertTrue(text(r.get("1")).contains("array of strings"), text(r.get("1")));
    }
}

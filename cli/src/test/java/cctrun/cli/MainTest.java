// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @TempDir
    Path dir;

    @Test
    void guideIsBundledAndAscii() throws Exception {
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.dispatch(new String[]{ "guide" }, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        var text = bytes.toString(StandardCharsets.US_ASCII);
        assertTrue(text.startsWith("# cct-run agent guide"));
        for (var b : bytes.toByteArray()) assertTrue(b >= 0, "guide must be ASCII only");
    }

    @Test
    void heapExhaustionIsItsOwnStatus() throws Exception {
        // A child JVM with a small heap, like the 1 GB the start scripts set, so the test is quick.
        var program = dir.resolve("hog.lua");
        Files.writeString(program, """
            local kept, n = {}, 0
            while true do
              n = n + 1
              kept[n] = string.rep("x", 4 * 1024 * 1024) .. n
              if n % 4 == 0 then os.queueEvent("t") os.pullEvent("t") end
            end
            """);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-Xmx128m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
            Main.class.getName(), "run", program.toString(), "--out", dir.resolve("runs").toString(), "--timeout", "60")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        assertEquals(Main.EX_OUT_OF_MEMORY, process.waitFor(), stdout);
        var summary = JsonParser.parseString(stdout).getAsJsonObject();
        assertEquals("out_of_memory", summary.get("status").getAsString());
        assertEquals(4, summary.get("exit_code").getAsInt());
        assertTrue(summary.getAsJsonObject("error").get("message").getAsString().contains("OutOfMemoryError"));
        var memory = summary.getAsJsonObject("memory");
        assertTrue(memory.get("heap_limit_mb").getAsLong() <= 128, memory.toString());
        assertTrue(memory.get("heap_peak_mb").getAsLong() >= 64, memory.toString());
        assertTrue(summary.get("duration").getAsJsonObject().get("ticks").getAsInt() < 60 * 20, "stopped by memory, not the timeout");
    }

    @Test
    void asciiEscapesEveryNonAsciiCharacter() {
        assertEquals("{\"a\":\"plain\"}", Main.ascii("{\"a\":\"plain\"}"));
        assertEquals("{\"a\":\"caf\\u00c3\\u00a9 \\u6d4b \\ud83d\\ude00\"}", Main.ascii("{\"a\":\"cafÃ© 测 😀\"}"));
    }

    @Test
    void runPrintsAsciiOnlyJsonThatDecodesToTheSameText() throws Exception {
        var program = dir.resolve("utf8.lua");
        Files.writeString(program, "print(\"caf\" .. string.char(195, 169))");
        var bytes = new ByteArrayOutputStream();
        var code = Main.dispatch(new String[]{ "run", program.toString(), "--out", dir.resolve("runs").toString() },
            new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(0, code);

        var raw = bytes.toByteArray();
        for (var b : raw) assertTrue(b >= 0, "stdout must be ASCII only");
        var json = JsonParser.parseString(new String(raw, StandardCharsets.US_ASCII)).getAsJsonObject();
        assertEquals("cafÃ©", json.getAsJsonObject("screen").getAsJsonArray("lines").get(0).getAsString());

        // Files on disk are still UTF-8 text, not escaped.
        var summary = Files.readString(Path.of(json.get("run_dir").getAsString()).resolve("summary.json"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("cafÃ©"));
    }
}

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

    /** Field test 4: an AI passed the run_id from the summary as the run and got "not a run directory". */
    @Test
    void traceAcceptsARunIdFromTheOutputDirectory() throws Exception {
        var program = dir.resolve("hi.lua");
        Files.writeString(program, "print('hi')");
        var runs = dir.resolve("runs").toString();
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.dispatch(new String[]{ "run", program.toString(), "--out", runs }, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        var runId = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("run_id").getAsString();

        bytes.reset();
        assertEquals(0, Main.dispatch(new String[]{ "trace", runId, "--out", runs, "--kind", "program_end" }, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("\"kind\":\"program_end\""));
    }

    @Test
    void traceLimitNoteAppearsOnlyWhenThereAreMoreRecords() throws Exception {
        var program = dir.resolve("hi.lua");
        Files.writeString(program, "print('hi')");
        var runs = dir.resolve("runs").toString();
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.dispatch(new String[]{ "run", program.toString(), "--out", runs }, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        var runDir = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("run_dir").getAsString();

        bytes.reset();
        assertEquals(0, Main.dispatch(new String[]{ "trace", runDir, "--limit", "1000" }, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        var total = (int) bytes.toString(StandardCharsets.UTF_8).lines().count();

        // Exactly at the limit is not "more": no note. One fewer: a note.
        var exact = captureErr(() -> Main.dispatch(new String[]{ "trace", runDir, "--limit", Integer.toString(total) },
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(!exact.contains("stopped at --limit"), exact);
        var fewer = captureErr(() -> Main.dispatch(new String[]{ "trace", runDir, "--limit", Integer.toString(total - 1) },
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(fewer.contains("stopped at --limit"), fewer);
    }

    @Test
    void traceReportsMalformedLinesItSkipped() throws Exception {
        var program = dir.resolve("hi.lua");
        Files.writeString(program, "print('hi')");
        var runs = dir.resolve("runs").toString();
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.dispatch(new String[]{ "run", program.toString(), "--out", runs }, new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        var runDir = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("run_dir").getAsString();
        Files.writeString(Path.of(runDir).resolve("trace.jsonl"), "{\"seq\":999,\"tick\":1,\"kind\":\"event\"",
            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        var err = captureErr(() -> Main.dispatch(new String[]{ "trace", runDir, "--limit", "1000" },
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
        assertTrue(err.contains("skipped 1 malformed trace line(s)"), err);
    }

    @Test
    void termOptionAcceptsSpacesAroundTheX() throws Exception {
        var program = dir.resolve("size.lua");
        Files.writeString(program, "print(term.getSize())");
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, Main.dispatch(new String[]{ "run", program.toString(), "--term", "26 x 20", "--out", dir.resolve("runs2").toString() },
            new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        var size = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject()
            .getAsJsonObject("screen").getAsJsonArray("size");
        assertEquals(26, size.get(0).getAsInt());
        assertEquals(20, size.get(1).getAsInt());
    }

    private static String captureErr(Action action) throws Exception {
        var buffer = new ByteArrayOutputStream();
        var previous = System.err;
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(previous);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
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

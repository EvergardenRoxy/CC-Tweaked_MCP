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

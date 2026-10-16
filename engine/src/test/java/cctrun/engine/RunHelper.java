// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/** Shared helpers: run a program and read its trace. */
final class RunHelper {
    private RunHelper() {
    }

    static RunResult run(Path outDir, String program, String fixture, int timeout, String... args) throws Exception {
        var parsed = fixture == null ? Fixture.empty() : Fixture.parse(fixture);
        return Runner.run(new RunSpec("prog.lua", program, List.of(args), parsed, fixture, timeout, null, null, outDir));
    }

    static List<JsonObject> trace(RunResult result) throws IOException {
        return Files.readAllLines(result.runDir().resolve("trace.jsonl"), StandardCharsets.UTF_8).stream()
            .map(line -> JsonParser.parseString(line).getAsJsonObject())
            .toList();
    }

    static List<JsonObject> trace(RunResult result, String kind) throws IOException {
        return trace(result).stream().filter(r -> r.get("kind").getAsString().equals(kind)).toList();
    }

    static List<JsonObject> trace(RunResult result, String kind, Predicate<JsonObject> filter) throws IOException {
        return trace(result, kind).stream().filter(filter).toList();
    }

    static List<String> screen(RunResult result) {
        return result.summary().getAsJsonObject("screen").getAsJsonArray("lines").asList().stream().map(e -> e.getAsString()).toList();
    }

    static List<String> monitor(RunResult result, String name) {
        return result.summary().getAsJsonObject("monitors").getAsJsonArray(name).asList().stream().map(e -> e.getAsString()).toList();
    }

    static JsonObject error(RunResult result) {
        return result.summary().getAsJsonObject("error");
    }
}

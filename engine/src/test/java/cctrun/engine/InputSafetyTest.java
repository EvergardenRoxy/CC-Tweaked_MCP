// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InputSafetyTest {
    @TempDir Path dir;
    @Test void completionRequiresTheTokenAndCannotReplaceAnAcceptedResult() throws Exception {
        try (var trace = new Trace(dir.resolve("completion.jsonl"), new Clock())) {
            var api = new HarnessApi(trace, TracingTerminal.create(10, 3, true, "term", trace), "expected");
            assertThrows(dan200.computercraft.api.lua.LuaException.class,
                () -> api.done(new dan200.computercraft.api.lua.ObjectArguments("wrong", true)));
            assertFalse(api.finished);
            api.done(new dan200.computercraft.api.lua.ObjectArguments("expected", true));
            assertThrows(dan200.computercraft.api.lua.LuaException.class,
                () -> api.done(new dan200.computercraft.api.lua.ObjectArguments("expected", false, "runtime", "replacement")));
            assertTrue(api.ok);
            assertTrue(api.finished);
        }
    }
    @Test void buildAndRuntimeReportOneVersion() {
        assertEquals(Versions.TOOL, System.getProperty("cctrun.build.version"));
    }
    @Test void interruptedRunWritesItsCauseAndRestoresTheInterruptFlag() throws Exception {
        var restored=new java.util.concurrent.atomic.AtomicBoolean();
        var future=new java.util.concurrent.FutureTask<RunResult>(()->{
            Thread.currentThread().interrupt();
            var result=RunHelper.run(dir,"while true do sleep(1) end",null,10);
            restored.set(Thread.currentThread().isInterrupted());return result;
        });
        new Thread(future).start();
        var result=future.get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("timeout",result.status());
        assertEquals("interrupted",result.summary().get("termination_reason").getAsString());
        assertTrue(Files.exists(result.runDir().resolve("summary.json")));
        assertTrue(restored.get());
    }

    @Test void deepValuesAreRejectedBeforeRecursiveConversion() {
        String value = "[".repeat(20_000) + "0" + "]".repeat(20_000);
        var error = assertThrows(FixtureException.class, () -> Fixture.parse("{\"timeline\":[{\"tick\":1,\"do\":\"event\",\"name\":\"x\",\"args\":" + value + "}]}"));
        assertTrue(error.getMessage().contains("$.timeline[0].args"));
        assertThrows(IllegalArgumentException.class, () -> JsonValues.toLua(JsonParser.parseString(value)));
        var boundary = "[".repeat(127) + "0" + "]".repeat(127);
        assertDoesNotThrow(() -> JsonValues.toLua(JsonParser.parseString(boundary)));
    }

    @Test void numericRangesAreExactAndDefaultsShared() throws Exception {
        for (String value : List.of("1e999", "9223372036854775808", "1.5", "-1")) {
            assertThrows(FixtureException.class, () -> Fixture.parse("{\"peripherals\":{\"left\":{\"type\":\"energy_storage\",\"capacity\":" + value + "}}}"));
        }
        assertDoesNotThrow(() -> Fixture.parse("{\"peripherals\":{\"left\":{\"type\":\"energy_storage\",\"capacity\":9223372036854775807}}}"));
        var fixture = Fixture.parse("{\"timeline\":[{\"tick\":3,\"do\":\"event\",\"name\":\"last\"},{\"tick\":1,\"do\":\"event\",\"name\":\"first\"},{\"tick\":1,\"do\":\"event\",\"name\":\"second\"}]}");
        assertEquals(List.of("first", "second", "last"), fixture.timeline.stream().map(a -> a.spec().get("name").getAsString()).toList());
    }

    @Test void directoriesAreAllocatedExclusivelyAndRunSpecRejectsInvalidInputs() throws Exception {
        Files.createDirectory(dir.resolve("stamp-same"));
        var counter = new AtomicInteger();
        var allocated = Runner.allocateRunDirectory(dir, "stamp", () -> counter.getAndIncrement() == 0 ? "same" : "different");
        assertEquals("stamp-different", allocated.getFileName().toString());
        for (var name : List.of("", ".", "..", "/")) assertThrows(IllegalArgumentException.class, () -> new RunSpec(name, "", List.of(), Fixture.empty(), null, 1, null, null, dir));
        assertThrows(IllegalArgumentException.class, () -> new RunSpec("x.lua", "", List.of(), Fixture.empty(), null, Integer.MAX_VALUE, null, null, dir));
    }

    @Test void missingAndWrongTraceFieldsDoNotAbortQueriesOrReplay() throws Exception {
        Files.writeString(dir.resolve("trace.jsonl"), "{}\n{\"tick\":[],\"kind\":true}\n{\"tick\":1,\"kind\":\"screen\",\"source\":\"term\",\"lines\":{\"99999999\":\"bad\"}}\n{\"tick\":2,\"kind\":\"event\",\"name\":\"good\"}\n");
        var result = TraceQuery.read(dir, new TraceQuery.Filter(null,null,null,null,null,0,100));
        assertEquals(3, result.skipped());
        assertEquals(1, result.records().size());
        assertNull(TraceQuery.screenAt(dir,"term",null));
    }

    @Test void ordinaryErrorsCannotSpoofOomAndMutatedGlobalsCannotBreakReporting() throws Exception {
        var forged = RunHelper.run(dir, "error('vm error: java.lang.OutOfMemoryError: fake')", null, 10);
        assertEquals("error", forged.status());
        var tampered = RunHelper.run(dir, "_G.tostring = function() error('broken') end _G.printError = nil _G.debug = nil error('original')", null, 10);
        assertEquals("error", tampered.status());
        assertTrue(RunHelper.error(tampered).get("message").getAsString().contains("original"));
    }
}

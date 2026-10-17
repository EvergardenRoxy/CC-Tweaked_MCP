// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static cctrun.engine.RunHelper.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TraceQueryTest {
    @TempDir
    Path out;

    @Test
    void screenCanBeReconstructedAtEarlierTicks() throws Exception {
        var result = run(out, "print('first') sleep(0.5) term.clear() term.setCursorPos(1, 1) print('second')", null, 10);
        var first = TraceQuery.screenAt(result.runDir(), "term", 3 + firstPrintTick(result));
        assertEquals(List.of("first"), first.lines());
        var last = TraceQuery.screenAt(result.runDir(), "term", null);
        assertEquals(List.of("second"), last.lines());
        assertEquals(51, last.width());
    }

    @Test
    void recordsAreFilteredByKindTickAndLimit() throws Exception {
        var result = run(out, "for i = 1, 3 do os.queueEvent('e', i) os.pullEvent('e') end", null, 10);
        var events = TraceQuery.records(result.runDir(), new TraceQuery.Filter(Set.of("event"), null, null, null, null, 0, 100))
            .stream().filter(r -> r.get("name").getAsString().equals("e")).toList();
        assertEquals(3, events.size());
        var all = TraceQuery.records(result.runDir(), new TraceQuery.Filter(Set.of("event"), null, null, null, null, 0, 100));
        var limited = TraceQuery.records(result.runDir(), new TraceQuery.Filter(Set.of("event"), null, null, null, null, 1, 1));
        assertEquals(List.of(all.get(1)), limited);
    }

    @Test
    void containsMatchesTextNamesAndArguments() throws Exception {
        var result = run(out, "os.queueEvent('chat', 'Steve', '!Factory Start') os.pullEvent('chat') print('done')", null, 10);
        var byArgument = TraceQuery.records(result.runDir(), new TraceQuery.Filter(null, null, null, null, null, "factory start", 0, 100));
        assertEquals(1, byArgument.size(), byArgument.toString());
        assertEquals("chat", byArgument.get(0).get("name").getAsString());
        var byName = TraceQuery.records(result.runDir(), new TraceQuery.Filter(Set.of("event"), null, null, null, null, "CHAT", 0, 100));
        assertEquals(byArgument, byName);
        var onScreen = TraceQuery.records(result.runDir(), new TraceQuery.Filter(Set.of("screen"), null, null, null, null, "done", 0, 100));
        assertEquals(1, onScreen.size());
    }

    private static int firstPrintTick(RunResult result) throws Exception {
        return RunHelper.trace(result, "screen").stream().filter(r -> r.toString().contains("first")).findFirst().orElseThrow().get("tick").getAsInt();
    }
}

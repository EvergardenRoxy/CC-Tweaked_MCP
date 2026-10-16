// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static cctrun.engine.RunHelper.error;
import static cctrun.engine.RunHelper.run;
import static cctrun.engine.RunHelper.screen;
import static cctrun.engine.RunHelper.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Program outcomes, screen and output capture, events, time. */
class RunnerTest {
    @TempDir
    Path out;

    @Test
    void okRunCapturesScreenArgumentsAndEnvironment() throws Exception {
        var result = run(out, """
            print(_HOST)
            print(select("#", ...), ...)
            print(arg[0], type(http), type(multishell), type(collectgarbage))
            """, null, 10, "a", "b c", "é\"\\");
        assertEquals("ok", result.status());
        assertEquals(0, result.exitCode());
        assertEquals(List.of(
            "ComputerCraft 1.120.2 (cct-run 0.1.0)",
            "3\ta\tb c\tÃ©\"\\", // CC strings are bytes: UTF-8 "é" shows as two characters
            "prog.lua\tnil\tnil\tnil"
        ), screen(result));
        assertTrue(Files.isRegularFile(result.runDir().resolve("summary.json")));
        assertTrue(Files.isRegularFile(result.runDir().resolve("program/prog.lua")));
    }

    @Test
    void syntaxErrorIsReportedWithTheGamesMessage() throws Exception {
        var result = run(out, "local x = 1\nif x == 1\n  print(x)\nend\n", null, 10);
        assertEquals("error", result.status());
        assertEquals(1, result.exitCode());
        assertEquals("syntax", error(result).get("phase").getAsString());
        assertEquals("/prog.lua:3: 'then' expected near 'print'", error(result).get("message").getAsString());
        // The screen shows CC: Tweaked's friendly parser message, as in game.
        assertEquals("Expected then after if condition.", screen(result).get(0));
    }

    @Test
    void runtimeErrorHasMessageAndTraceback() throws Exception {
        var result = run(out, """
            local function draw()
                local mon = peripheral.find("monitor")
                mon.write("x")
            end
            draw()
            """, null, 10);
        assertEquals("error", result.status());
        assertEquals("runtime", error(result).get("phase").getAsString());
        assertEquals("/prog.lua:3: attempt to index local 'mon' (a nil value)", error(result).get("message").getAsString());
        assertTrue(error(result).get("traceback").getAsString().contains("/prog.lua:3: in local 'draw'"));
        assertEquals(1, trace(result, "program_end").size());
        assertEquals(1, result.summary().getAsJsonArray("notes").size());
    }

    @Test
    void timeoutStopsAWaitingProgram() throws Exception {
        var result = run(out, "print('waiting') os.pullEvent('redstone')", null, 1);
        assertEquals("timeout", result.status());
        assertEquals(2, result.exitCode());
        assertEquals(List.of("waiting"), screen(result));
        assertEquals(20, result.summary().getAsJsonObject("duration").get("ticks").getAsInt());
        assertFalse(result.summary().has("timeout_state"), "no VM state while waiting for an event");
    }

    @Test
    void tooLongWithoutYieldingAbortsAfterAboutSevenSeconds() throws Exception {
        var result = run(out, "while true do end", null, 15);
        assertEquals("error", result.status());
        assertEquals("/prog.lua:1: Too long without yielding", error(result).get("message").getAsString());
        var seconds = result.summary().getAsJsonObject("duration").get("seconds").getAsDouble();
        assertTrue(seconds > 6.5 && seconds < 8.5, "aborted after " + seconds + "s");
    }

    @Test
    void shutdownAndRebootByTheProgram() throws Exception {
        var shutdown = run(out, "print('bye') os.shutdown()", null, 10);
        assertEquals("shutdown", shutdown.status());
        assertEquals(3, shutdown.exitCode());
        assertEquals(List.of("bye"), screen(shutdown), "screen captured before the shutdown cleared it");

        var reboot = run(out, "os.reboot()", null, 10);
        assertEquals("reboot", reboot.status());
        assertEquals(3, reboot.exitCode());
    }

    @Test
    void eventsAreRecordedIncludingTimersAndQueuedEvents() throws Exception {
        var result = run(out, """
            os.queueEvent("custom", 42, "x")
            os.pullEvent("custom")
            os.startTimer(0.1)
            os.pullEvent("timer")
            """, null, 10);
        assertEquals("ok", result.status());
        // Boot also delivers setting_changed events (applying default settings), as in game.
        var events = trace(result, "event", r -> !r.get("name").getAsString().equals("setting_changed"));
        assertEquals("custom", events.get(0).get("name").getAsString());
        assertEquals(42, events.get(0).getAsJsonArray("args").get(0).getAsInt());
        assertEquals("timer", events.get(1).get("name").getAsString());
    }

    @Test
    void cctRunsOwnSettingEventsAreFlaggedAndProgramStartIsMarked() throws Exception {
        var result = run(out, "settings.set('motd.enable', true) os.pullEvent('setting_changed') sleep(2.5)", null, 10);
        assertEquals("ok", result.status());
        var settingEvents = trace(result, "event", r -> r.get("name").getAsString().equals("setting_changed"));
        // Two from cct-run's defaults (flagged), one from the program (not flagged).
        assertEquals(3, settingEvents.size());
        assertEquals(2, settingEvents.stream().filter(r -> r.has("harness")).count());
        var programs = settingEvents.stream().filter(r -> !r.has("harness")).toList();
        assertEquals("motd.enable", programs.get(0).getAsJsonArray("args").get(0).getAsString());
        assertTrue(programs.get(0).getAsJsonArray("args").get(1).getAsBoolean());
        assertEquals(1, trace(result, "program_start").size());

        var rate = result.summary().getAsJsonObject("duration").get("tick_rate").getAsDouble();
        assertTrue(rate > 15 && rate < 25, "tick_rate " + rate);
    }

    @Test
    void linesThatScrollOffAreRecordedAsOutput() throws Exception {
        var result = run(out, "for i = 1, 25 do print('line ' .. i) end", null, 10);
        var output = trace(result, "output").stream().map(r -> r.get("text").getAsString()).toList();
        // 25 lines plus the cursor's empty line on a 19-line screen: lines 1-7 scroll off.
        assertEquals(List.of("line 1", "line 2", "line 3", "line 4", "line 5", "line 6", "line 7"), output);
        assertEquals("line 8", screen(result).get(0));
    }

    @Test
    void multishellCanBeEnabled() throws Exception {
        var result = run(out, "print(type(multishell))", "{\"computer\": {\"multishell\": true}}", 10);
        assertEquals(List.of("table"), screen(result));
    }

    @Test
    void programCalledStartupIsStoredAsMain() throws Exception {
        var spec = new RunSpec("startup.lua", "print(shell and 'ok')", List.of(), Fixture.empty(), null, 10, null, null, out);
        var result = Runner.run(spec);
        assertEquals("ok", result.status());
        assertEquals("/main.lua", result.summary().getAsJsonObject("input").getAsJsonObject("program").get("path").getAsString());
    }

    @Test
    void basicComputerAndTermSize() throws Exception {
        var spec = new RunSpec("prog.lua", "print(term.isColour(), term.getSize())", List.of(), Fixture.empty(), null, 10,
            new int[]{ 26, 20 }, false, out);
        var result = Runner.run(spec);
        assertEquals(List.of("false\t26\t20"), screen(result));
        assertTrue(result.summary().get("error").isJsonNull());
    }
}

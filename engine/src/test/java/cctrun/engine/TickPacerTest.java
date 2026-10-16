// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TickPacer against a simulated clock, including Windows' default 15.625 ms sleep granularity. */
class TickPacerTest {
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long WINDOWS_GRANULARITY = 15_625_000; // 1/64 s

    private long now = 1_000_000_000L;

    /** Sleeps are rounded up to the timer granularity, as on Windows without a high-resolution timer. */
    private void coarseSleep(long nanos) {
        now += (nanos + WINDOWS_GRANULARITY - 1) / WINDOWS_GRANULARITY * WINDOWS_GRANULARITY;
    }

    @Test
    void keepsTwentyTicksPerSecondDespiteCoarseSleeps() throws Exception {
        var pacer = new TickPacer(50 * MS, 1000 * MS, () -> now, this::coarseSleep);
        var start = now;
        for (var tick = 0; tick < 200; tick++) {
            now += 2 * MS; // work done in the tick
            pacer.awaitTick(tick + 1);
        }
        // 200 ticks at 20/s = 10 s; with a fixed 50 ms sleep per tick this would be 12.5 s (16 ticks/s).
        var elapsedMs = (now - start) / MS;
        assertTrue(Math.abs(elapsedMs - 10_000) <= 20, "elapsed " + elapsedMs + " ms");
        assertEquals(20.0, pacer.tickRate(200), 0.1);
        assertEquals(0, pacer.lagResets());
    }

    @Test
    void givesUpMissedTicksAfterALongStall() throws Exception {
        var pacer = new TickPacer(50 * MS, 1000 * MS, () -> now, nanos -> now += nanos);
        pacer.awaitTick(1);
        now += 3000 * MS; // e.g. the machine was suspended
        var behind = pacer.awaitTick(2);
        assertTrue(behind > 1000 * MS);
        assertEquals(1, pacer.lagResets());
        // Paced normally again from here, not 60 ticks back to back.
        var before = now;
        pacer.awaitTick(3);
        assertEquals(50 * MS, now - before);
    }
}

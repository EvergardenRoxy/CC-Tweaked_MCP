// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Paces the tick loop against absolute deadlines ({@code start + n * period}) instead of sleeping a fixed amount
 * after each tick. Oversleeping one tick (Windows rounds sleeps up to its default 15.6 ms timer granularity, turning
 * 50 ms into 62.5 ms) shortens the next wait, so the average stays at the target rate.
 * <p>
 * If the loop falls more than {@code maxLag} behind (machine suspended, long GC pause), the missed ticks are given up
 * instead of being run back to back, like a Minecraft server that "can't keep up".
 */
final class TickPacer {
    @FunctionalInterface
    interface Sleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long period;
    private final long maxLag;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final long firstStart;
    private long start;
    private int lagResets;

    TickPacer(long periodNanos, long maxLagNanos, LongSupplier nanoTime, Sleeper sleeper) {
        this.period = periodNanos;
        this.maxLag = maxLagNanos;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.firstStart = this.start = nanoTime.getAsLong();
    }

    static TickPacer realTime(int ticksPerSecond) {
        return new TickPacer(TimeUnit.SECONDS.toNanos(1) / ticksPerSecond, TimeUnit.SECONDS.toNanos(1), System::nanoTime,
            TimeUnit.NANOSECONDS::sleep);
    }

    /**
     * Wait until the next tick is due.
     *
     * @param ticksDone number of ticks finished so far; the next tick is due at {@code start + ticksDone * period}
     * @return nanoseconds the loop was behind when it gave up on missed ticks, or 0
     */
    long awaitTick(int ticksDone) throws InterruptedException {
        var due = start + ticksDone * period;
        var now = nanoTime.getAsLong();
        if (due > now) {
            sleeper.sleep(due - now);
            return 0;
        }
        var behind = now - due;
        if (behind <= maxLag) return 0;
        start = now - ticksDone * period;
        lagResets++;
        return behind;
    }

    /** Measured ticks per second since the pacer was created. */
    double tickRate(int ticksDone) {
        var elapsed = nanoTime.getAsLong() - firstStart;
        return elapsed <= 0 ? 0 : ticksDone / (elapsed / 1e9);
    }

    int lagResets() {
        return lagResets;
    }
}

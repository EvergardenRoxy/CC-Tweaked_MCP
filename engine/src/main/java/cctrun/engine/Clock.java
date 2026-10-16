package cctrun.engine;

/** Game tick counter plus wall-clock time since the run started. Shared by every recorder of one run. */
public final class Clock {
    private final long startNanos = System.nanoTime();
    private volatile int tick;

    public int tick() {
        return tick;
    }

    void setTick(int tick) {
        this.tick = tick;
    }

    /** Milliseconds since the run started, rounded to 0.1 ms. */
    public double millis() {
        return Math.round((System.nanoTime() - startNanos) / 100_000.0) / 10.0;
    }
}

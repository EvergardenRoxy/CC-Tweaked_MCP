package cctrun.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Everything needed for one run.
 *
 * @param programName    file name of the program, as shown in error messages (e.g. {@code mine.lua})
 * @param programSource  program text
 * @param args           arguments passed to the program ({@code ...} and {@code arg})
 * @param fixture        world around the computer; {@link Fixture#empty()} for none
 * @param fixtureSource  fixture text as given (stored in the run directory), or null
 * @param timeoutSeconds stop after this many seconds of game time (20 ticks per second, real time)
 * @param termSize       {width, height}, or null for the fixture's value or 51x19
 * @param advanced       advanced (colour) computer, or null for the fixture's value or true
 * @param outDir         parent directory for run directories
 */
public record RunSpec(String programName, String programSource, List<String> args, Fixture fixture, String fixtureSource,
                      int timeoutSeconds, int[] termSize, Boolean advanced, Path outDir) {
    public RunSpec {
        Objects.requireNonNull(programName);
        Objects.requireNonNull(programSource);
        args = List.copyOf(args);
        Objects.requireNonNull(fixture);
        if (timeoutSeconds < 1) throw new IllegalArgumentException("timeoutSeconds must be >= 1");
        Objects.requireNonNull(outDir);
    }
}

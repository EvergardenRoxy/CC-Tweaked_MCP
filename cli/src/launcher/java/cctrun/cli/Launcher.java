// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point of the cct-run start scripts. Compiled for Java 8, so it can run on whatever {@code java} the
 * scripts found (JAVA_HOME, else PATH) and explain or fix a too-old Java instead of failing with
 * UnsupportedClassVersionError:
 * <ul>
 *   <li>Java 17 or newer: run {@link Main} directly (loaded by reflection, since Main needs Java 17).</li>
 *   <li>Older: look for an installed Java 17+ (JavaFinder) and run cct-run again with it, passing stdio and the
 *       exit code through. If there is none, print what was found and exit 69.</li>
 * </ul>
 * Messages are ASCII-only English on stderr, so they survive any console code page.
 */
public final class Launcher {
    static final int MIN_MAJOR = 17;
    static final int EX_UNAVAILABLE = 69;
    private static final String REEXEC_MARKER = "CCT_RUN_REEXEC";

    private Launcher() {
    }

    public static void main(String[] args) throws Throwable {
        int current = JavaFinder.major(System.getProperty("java.specification.version"));
        if (current >= MIN_MAJOR) {
            runMain(args);
            return;
        }

        String currentDesc = "Java " + System.getProperty("java.version") + " at " + System.getProperty("java.home");
        String onlyRoots = System.getProperty("cctrun.java.searchOnly");
        List<File> roots = null;
        if (onlyRoots != null) {
            roots = new ArrayList<File>();
            for (String root : onlyRoots.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!root.isEmpty()) roots.add(new File(root));
            }
        }
        List<JavaFinder.Home> homes = JavaFinder.findAll(System.getenv("JAVA_HOME"), System.getenv("PATH"), roots);
        JavaFinder.Home best = JavaFinder.best(homes, MIN_MAJOR);

        if (best == null || System.getenv(REEXEC_MARKER) != null) {
            System.err.println("cct-run: needs Java " + MIN_MAJOR + " or newer, but was started with " + currentDesc + ".");
            if (homes.isEmpty()) {
                System.err.println("  No other Java installation was found.");
            } else {
                System.err.println("  Java installations found:");
                for (JavaFinder.Home home : homes) System.err.println("    " + home);
            }
            System.err.println("  Install a JDK " + MIN_MAJOR + "+ (e.g. Eclipse Temurin or Azul Zulu), or set JAVA_HOME to one:");
            System.err.println("    PowerShell: $env:JAVA_HOME = \"C:\\Program Files\\...\\jdk-21\"");
            System.err.println("    cmd:        set JAVA_HOME=C:\\Program Files\\...\\jdk-21");
            System.err.println("    bash/zsh:   export JAVA_HOME=/path/to/jdk-21");
            System.exit(EX_UNAVAILABLE);
            return;
        }

        System.err.println("cct-run: " + currentDesc + " is too old; using " + best + ".");
        List<String> command = new ArrayList<String>();
        command.add(best.java().getPath());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Launcher.class.getName());
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.environment().put(REEXEC_MARKER, "1");
        Process process = builder.start();
        System.exit(process.waitFor());
    }

    private static void runMain(String[] args) throws Throwable {
        try {
            Class.forName("cctrun.cli.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds installed Java runtimes and their versions. Compiled for Java 8 (see Launcher), so it only uses Java 8 APIs.
 * <p>
 * A Java home is a directory with {@code bin/java} (or {@code bin\java.exe}) and a {@code release} file, whose
 * {@code JAVA_VERSION} line gives the version without starting the JVM. Minecraft launchers such as Prism and PCL CE
 * use the system JDK by default (handoff.md D23), so only JAVA_HOME, PATH and the usual JDK install folders are
 * searched, not launcher folders.
 */
final class JavaFinder {
    /** A Java installation. */
    static final class Home {
        final File dir;
        final String version;
        final int major;

        Home(File dir, String version, int major) {
            this.dir = dir;
            this.version = version;
            this.major = major;
        }

        File java() {
            return javaExecutable(dir);
        }

        @Override
        public String toString() {
            return "Java " + version + " at " + dir;
        }
    }

    private static final Pattern JAVA_VERSION = Pattern.compile("^JAVA_VERSION=\"?([^\"]+)\"?\\s*$");
    private static final Pattern VENDOR_DIR = Pattern.compile(
        "(?i).*(java|jdk|jre|zulu|adoptium|temurin|eclipse|microsoft|bellsoft|liberica|corretto|semeru|graalvm|openjdk|dragonwell|kona).*");

    private JavaFinder() {
    }

    /** Major version from a version string: "1.8.0_382" -> 8, "21.0.2" -> 21, "17" -> 17, "25-ea" -> 25; -1 if unknown. */
    static int major(String version) {
        if (version == null) return -1;
        Matcher m = Pattern.compile("^(\\d+)(?:\\.(\\d+))?").matcher(version.trim());
        if (!m.find()) return -1;
        int first = Integer.parseInt(m.group(1));
        if (first == 1 && m.group(2) != null) return Integer.parseInt(m.group(2));
        return first;
    }

    static File javaExecutable(File home) {
        File exe = new File(new File(home, "bin"), "java.exe");
        return exe.isFile() ? exe : new File(new File(home, "bin"), "java");
    }

    /** The home described by {@code dir}, or null if it is not a Java home with a readable release file. */
    static Home inspect(File dir) {
        if (dir == null || !dir.isDirectory() || !javaExecutable(dir).isFile()) return null;
        File release = new File(dir, "release");
        if (!release.isFile()) return null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(release), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher m = JAVA_VERSION.matcher(line);
                if (m.matches()) {
                    int major = major(m.group(1));
                    return major < 0 ? null : new Home(canonical(dir), m.group(1), major);
                }
            }
        } catch (IOException ignored) {
            // Unreadable: not usable.
        }
        return null;
    }

    /**
     * Every Java home found, in search order: JAVA_HOME, PATH, then the usual install folders for this OS.
     * If {@code onlyRoots} is not null, only those roots (and their direct children) are searched (for tests).
     */
    static List<Home> findAll(String javaHome, String path, List<File> onlyRoots) {
        Set<File> dirs = new LinkedHashSet<File>();
        if (onlyRoots == null) {
            if (javaHome != null && !javaHome.trim().isEmpty()) dirs.add(canonical(new File(javaHome.trim())));
            if (path != null) {
                for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                    if (entry.trim().isEmpty()) continue;
                    File bin = new File(entry.trim().replace("\"", ""));
                    File java = new File(bin, "java.exe").isFile() ? new File(bin, "java.exe") : new File(bin, "java");
                    if (!java.isFile()) continue;
                    // /usr/bin/java is usually a link into the real home: resolve it.
                    File real = canonical(java);
                    if (real.getParentFile() != null && real.getParentFile().getParentFile() != null) {
                        dirs.add(real.getParentFile().getParentFile());
                    }
                }
            }
        }
        for (File root : onlyRoots != null ? onlyRoots : defaultRoots()) addUnder(dirs, root, onlyRoots != null);

        List<Home> homes = new ArrayList<Home>();
        for (File dir : dirs) {
            Home home = inspect(dir);
            if (home != null) homes.add(home);
        }
        return homes;
    }

    /** The newest home with at least {@code minMajor}, or null. */
    static Home best(List<Home> homes, int minMajor) {
        Home best = null;
        for (Home home : homes) {
            if (home.major >= minMajor && (best == null || home.major > best.major)) best = home;
        }
        return best;
    }

    private static void addUnder(Set<File> dirs, File root, boolean anyChild) {
        if (root == null || !root.isDirectory()) return;
        dirs.add(canonical(root));
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            dirs.add(canonical(child));
            // macOS bundles keep the home in Contents/Home.
            File macHome = new File(new File(child, "Contents"), "Home");
            if (macHome.isDirectory()) dirs.add(canonical(macHome));
            // Vendor folders such as "Program Files\Zulu" hold one home per version.
            if (!anyChild && VENDOR_DIR.matcher(child.getName()).matches()) {
                File[] versions = child.listFiles();
                if (versions != null) for (File v : versions) if (v.isDirectory()) dirs.add(canonical(v));
            }
        }
    }

    private static List<File> defaultRoots() {
        List<File> roots = new ArrayList<File>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", "");
        if (os.contains("win")) {
            // Program Files etc. are searched one level down only through vendor-named folders (addUnder).
            for (String env : new String[]{ "ProgramFiles", "ProgramW6432", "ProgramFiles(x86)" }) {
                String dir = System.getenv(env);
                if (dir != null) roots.add(new File(dir));
            }
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) roots.add(new File(local, "Programs"));
            roots.add(new File(userHome, ".jdks"));
        } else if (os.contains("mac")) {
            roots.add(new File("/Library/Java/JavaVirtualMachines"));
            roots.add(new File(userHome, "Library/Java/JavaVirtualMachines"));
            roots.add(new File(userHome, ".sdkman/candidates/java"));
            roots.add(new File(userHome, ".jdks"));
        } else {
            roots.add(new File("/usr/lib/jvm"));
            roots.add(new File("/usr/java"));
            roots.add(new File("/opt"));
            roots.add(new File(userHome, ".sdkman/candidates/java"));
            roots.add(new File(userHome, ".jdks"));
        }
        return roots;
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }
}

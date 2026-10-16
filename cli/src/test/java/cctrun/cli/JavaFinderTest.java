// SPDX-License-Identifier: MPL-2.0

package cctrun.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaFinderTest {
    @TempDir
    Path dir;

    private Path fakeHome(Path parent, String name, String releaseVersion) throws IOException {
        var home = parent.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "");
        if (releaseVersion != null) Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Test\"\nJAVA_VERSION=\"" + releaseVersion + "\"\n");
        return home;
    }

    @Test
    void parsesMajorVersions() {
        assertEquals(8, JavaFinder.major("1.8.0_382"));
        assertEquals(8, JavaFinder.major("1.8"));
        assertEquals(11, JavaFinder.major("11.0.21"));
        assertEquals(17, JavaFinder.major("17"));
        assertEquals(21, JavaFinder.major("21.0.12.1"));
        assertEquals(25, JavaFinder.major("25-ea"));
        assertEquals(-1, JavaFinder.major("weird"));
    }

    @Test
    void picksTheNewestJava17PlusUnderTheRoots() throws IOException {
        var root = dir.resolve("jvm");
        fakeHome(root, "zulu-8", "1.8.0_382");
        fakeHome(root, "zulu-17", "17.0.9");
        fakeHome(root, "zulu-21", "21.0.2");
        fakeHome(root, "broken", null); // no release file: ignored

        var homes = JavaFinder.findAll(null, null, List.of(root.toFile()));
        assertEquals(3, homes.size());
        var best = JavaFinder.best(homes, 17);
        assertEquals(21, best.major);
        assertEquals("zulu-21", best.dir.getName());
        assertEquals(new File(best.dir, "bin" + File.separator + "java"), best.java());
    }

    @Test
    void nothingRecentEnough() throws IOException {
        var root = dir.resolve("jvm");
        fakeHome(root, "jdk8", "1.8.0_382");
        fakeHome(root, "jdk11", "11.0.21");
        assertNull(JavaFinder.best(JavaFinder.findAll(null, null, List.of(root.toFile())), 17));
    }

    @Test
    void javaHomeAndPathAreSearchedFirst() throws IOException {
        var viaHome = fakeHome(dir, "home-jdk", "17.0.1");
        var viaPath = fakeHome(dir, "path-jdk", "21.0.1");
        var homes = JavaFinder.findAll(viaHome.toString(), viaPath.resolve("bin").toString(), null);
        assertEquals(viaHome.toFile().getCanonicalFile(), homes.get(0).dir);
        assertEquals(viaPath.toFile().getCanonicalFile(), homes.get(1).dir);
    }
}

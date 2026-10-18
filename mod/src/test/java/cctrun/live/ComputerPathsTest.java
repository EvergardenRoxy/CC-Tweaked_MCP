// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Path sanitising for the files endpoint: nothing may address a path outside the computer's directory. */
class ComputerPathsTest {
    @Test
    void keepsNormalPathsRelative() {
        assertEquals("a/b", ComputerPaths.sanitise("/a/./b/"));
        assertEquals("", ComputerPaths.sanitise("/"));
        assertEquals("startup.lua", ComputerPaths.sanitise("startup.lua"));
        assertEquals("rom/apis/peripheral.lua", ComputerPaths.sanitise("rom/apis/peripheral.lua"));
    }

    @Test
    void rejectsParentTraversal() {
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.sanitise("../secret"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.sanitise("a/../../b"));
    }

    @Test
    void checkRelativeOnlyAcceptsTheSanitisedForm() {
        assertEquals("a/b", ComputerPaths.checkRelative("a/b"));
        assertEquals("startup.lua", ComputerPaths.checkRelative("startup.lua"));
        // Snapshot manifests are files on disk: every other spelling is refused instead of resolved.
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.checkRelative("../x"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.checkRelative("C:/x"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.checkRelative("/abs"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.checkRelative("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.checkRelative("a//b"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.checkRelative(""));
    }

    @Test
    void rejectsWindowsDriveLetters() {
        // Regression: "C:/Windows/win.ini" used to reach the host filesystem through WritableFileMount.resolvePath.
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.sanitise("C:/Windows/win.ini"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.sanitise("C:x"));
        assertThrows(IllegalArgumentException.class, () -> ComputerPaths.sanitise("c:/Users/x/.ssh/id_rsa"));
        // A UNC path loses its leading slashes and becomes a harmless relative path (never an absolute one).
        assertEquals("server/share/x", ComputerPaths.sanitise("\\\\server\\share\\x"));
    }
}

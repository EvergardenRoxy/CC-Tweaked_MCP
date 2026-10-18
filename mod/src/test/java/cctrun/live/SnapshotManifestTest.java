// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A7: a snapshot manifest is data from disk, so its paths are checked before restore() resolves them. */
class SnapshotManifestTest {
    @Test
    void readsThePathsAndTheSaveTime() {
        var manifest = SnapshotManifest.parse("""
            {"id": 2, "name": "before", "saved": "2026-10-18T00:00:00Z",
             "files": {"startup.lua": "abc", "rom/programs/x.lua": "def"},
             "dirs": ["rom", "rom/programs"]}
            """);
        assertEquals(java.util.Set.of("startup.lua", "rom/programs/x.lua"), manifest.files().keySet());
        assertEquals("abc", manifest.files().get("startup.lua"));
        assertEquals(java.util.List.of("rom", "rom/programs"), manifest.dirs().stream().toList());
        assertEquals("2026-10-18T00:00:00Z", manifest.saved());
    }

    @Test
    void rejectsAFileThatWouldEscapeTheSnapshot() {
        for (var path : java.util.List.of("../startup.lua", "C:/Windows/win.ini", "/etc/passwd", "a\\b", "a//b")) {
            assertThrows(IllegalArgumentException.class, () -> SnapshotManifest.parse(
                "{\"files\": {\"" + path.replace("\\", "\\\\") + "\": \"x\"}, \"dirs\": []}"), path);
        }
    }

    @Test
    void rejectsADirectoryThatWouldEscapeTheSnapshot() {
        for (var path : java.util.List.of("..", "C:/Windows", "/etc")) {
            assertThrows(IllegalArgumentException.class, () -> SnapshotManifest.parse(
                "{\"files\": {}, \"dirs\": [\"" + path + "\"]}"), path);
        }
    }

    @Test
    void rejectsAManifestWithoutFilesOrDirs() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifest.parse("{\"dirs\": []}"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifest.parse("{\"files\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotManifest.parse("{\"files\": [], \"dirs\": []}"));
    }
}

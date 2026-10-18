// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The manifest of a cct-live snapshot, without any Minecraft classes (so it can be unit-tested; see A7 in the review).
 * Every path in it is checked with {@link ComputerPaths#checkRelative}: a manifest is written next to the files it
 * names, and a path like {@code ../../startup.lua} or {@code C:/Windows/win.ini} must never escape the snapshot.
 * Server thread only.
 */
final class SnapshotManifest {
    private final TreeMap<String, String> files;
    private final TreeSet<String> dirs;
    private final String saved;

    private SnapshotManifest(TreeMap<String, String> files, TreeSet<String> dirs, String saved) {
        this.files = files;
        this.dirs = dirs;
        this.saved = saved;
    }

    static SnapshotManifest parse(String json) {
        var object = JsonParser.parseString(json).getAsJsonObject();
        if (!object.has("files") || !object.get("files").isJsonObject()) {
            throw new IllegalArgumentException("snapshot manifest has no 'files' object");
        }
        if (!object.has("dirs") || !object.get("dirs").isJsonArray()) {
            throw new IllegalArgumentException("snapshot manifest has no 'dirs' array");
        }
        var files = new TreeMap<String, String>();
        for (var e : object.getAsJsonObject("files").entrySet()) {
            files.put(ComputerPaths.checkRelative(e.getKey()), e.getValue().getAsString());
        }
        var dirs = new TreeSet<String>();
        for (var e : object.getAsJsonArray("dirs")) dirs.add(ComputerPaths.checkRelative(e.getAsString()));
        var saved = object.has("saved") && !object.get("saved").isJsonNull() ? object.get("saved").getAsString() : null;
        return new SnapshotManifest(files, dirs, saved);
    }

    TreeMap<String, String> files() {
        return files;
    }

    TreeSet<String> dirs() {
        return dirs;
    }

    String saved() {
        return saved;
    }
}

// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Snapshots of a computer's own files, so an AI can put a computer back the way it found it (D46): save copies every
 * file (except /.cctlive) to {@code cct-live/snapshots/computer-<id>/<name>/}; diff compares the computer with it;
 * restore writes back changed and missing files and deletes files that were not there (needs files.allow_write).
 * Snapshots stay on disk across game sessions. Server thread only.
 */
final class ComputerSnapshots {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final String MANIFEST = "manifest.json";

    private ComputerSnapshots() {
    }

    static String checkName(String name) {
        if (name == null || name.isEmpty()) return "before";
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("snapshot name: 1-40 letters, digits, '_' or '-'");
        return name;
    }

    private static Path dir(Path home, int id, String name) {
        return home.resolve("snapshots").resolve("computer-" + id).resolve(name);
    }

    /** Files and directories of the computer, without the temporary directory. */
    private record Tree(TreeMap<String, String> files, TreeMap<String, Long> sizes, TreeSet<String> dirs) {
    }

    private static Tree scan(WritableMount mount) throws IOException {
        var tree = new Tree(new TreeMap<>(), new TreeMap<>(), new TreeSet<>());
        walk(mount, "", tree);
        return tree;
    }

    private static void walk(WritableMount mount, String path, Tree tree) throws IOException {
        var names = new ArrayList<String>();
        mount.list(path, names);
        for (var name : names) {
            var child = path.isEmpty() ? name : path + "/" + name;
            if (child.equals(ComputerFiles.TEMP_DIR)) continue;
            if (mount.isDirectory(child)) {
                tree.dirs().add(child);
                walk(mount, child, tree);
            } else {
                tree.files().put(child, sha256(readAll(mount, child)));
                tree.sizes().put(child, mount.getSize(child));
            }
        }
    }

    private static byte[] readAll(WritableMount mount, String path) throws IOException {
        var buffer = ByteBuffer.allocate((int) mount.getSize(path));
        try (var channel = mount.openForRead(path)) {
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading
            }
        }
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject save(ServerComputer computer, Path home, String rawName) throws IOException {
        var name = checkName(rawName);
        var mount = computer.createRootMount();
        var tree = scan(mount);
        var dir = dir(home, computer.getID(), name);
        deleteTree(dir);
        var filesDir = dir.resolve("files");
        Files.createDirectories(filesDir);
        var manifest = new JsonObject();
        manifest.addProperty("id", computer.getID());
        manifest.addProperty("name", name);
        manifest.addProperty("saved", java.time.Instant.now().toString());
        var files = new JsonObject();
        long bytes = 0;
        for (var path : tree.files().keySet()) {
            var target = filesDir.resolve(path);
            Files.createDirectories(target.getParent());
            var data = readAll(mount, path);
            Files.write(target, data);
            bytes += data.length;
            files.addProperty(path, tree.files().get(path));
        }
        manifest.add("files", files);
        manifest.add("dirs", strings(tree.dirs()));
        Files.writeString(dir.resolve(MANIFEST), manifest.toString(), StandardCharsets.UTF_8);

        var out = new JsonObject();
        out.addProperty("id", computer.getID());
        out.addProperty("name", name);
        out.addProperty("files", tree.files().size());
        out.addProperty("dirs", tree.dirs().size());
        out.addProperty("bytes", bytes);
        out.addProperty("dir", dir.toString());
        return out;
    }

    private static SnapshotManifest manifest(Path dir) throws IOException {
        var file = dir.resolve(MANIFEST);
        if (!Files.exists(file)) throw new IllegalArgumentException("no snapshot '" + dir.getFileName() + "' (save one first)");
        // A snapshot manifest is plain data: SnapshotManifest checks every path before anything resolves it (review A7).
        return SnapshotManifest.parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** What differs from the snapshot: added / removed / changed files (paths), added / removed directories. */
    static JsonObject diff(ServerComputer computer, Path home, String rawName) throws IOException {
        var name = checkName(rawName);
        var dir = dir(home, computer.getID(), name);
        var snap = manifest(dir);
        var now = scan(computer.createRootMount());
        var out = new JsonObject();
        out.addProperty("id", computer.getID());
        out.addProperty("name", name);
        out.addProperty("saved", snap.saved());
        describe(out, snap, now);
        return out;
    }

    private static boolean describe(JsonObject out, SnapshotManifest snap, Tree now) {
        var added = new TreeSet<>(now.files().keySet());
        added.removeAll(snap.files().keySet());
        var removed = new TreeSet<>(snap.files().keySet());
        removed.removeAll(now.files().keySet());
        var changed = new TreeSet<String>();
        for (var e : snap.files().entrySet()) {
            var current = now.files().get(e.getKey());
            if (current != null && !current.equals(e.getValue())) changed.add(e.getKey());
        }
        var dirsAdded = new TreeSet<>(now.dirs());
        dirsAdded.removeAll(snap.dirs());
        var dirsRemoved = new TreeSet<>(snap.dirs());
        dirsRemoved.removeAll(now.dirs());
        var same = added.isEmpty() && removed.isEmpty() && changed.isEmpty() && dirsAdded.isEmpty() && dirsRemoved.isEmpty();
        out.addProperty("identical", same);
        out.add("added", strings(added));
        out.add("removed", strings(removed));
        out.add("changed", strings(changed));
        out.add("dirs_added", strings(dirsAdded));
        out.add("dirs_removed", strings(dirsRemoved));
        return same;
    }

    /** Puts the computer's files back as saved; returns what it did and checks the result. */
    static JsonObject restore(ServerComputer computer, Path home, String rawName) throws IOException {
        var name = checkName(rawName);
        var dir = dir(home, computer.getID(), name);
        var snap = manifest(dir);
        var mount = computer.createRootMount();
        var now = scan(mount);

        var deleted = new ArrayList<String>();
        for (var path : now.files().keySet()) {
            if (!snap.files().containsKey(path)) {
                mount.delete(path);
                deleted.add(path);
            }
        }
        // Deepest first, and only directories the snapshot did not have (they may still hold kept files: skip those).
        for (var path : now.dirs().descendingSet()) {
            if (snap.dirs().contains(path) || !mount.exists(path)) continue;
            var left = new ArrayList<String>();
            mount.list(path, left);
            if (left.isEmpty()) {
                mount.delete(path);
                deleted.add(path + "/");
            }
        }
        for (var path : snap.dirs()) if (!mount.exists(path)) mount.makeDirectory(path);
        var written = new ArrayList<String>();
        for (var e : snap.files().entrySet()) {
            var path = e.getKey();
            if (e.getValue().equals(now.files().get(path))) continue;
            if (mount.exists(path) && mount.isDirectory(path)) mount.delete(path);
            var data = Files.readAllBytes(dir.resolve("files").resolve(path));
            var slash = path.lastIndexOf('/');
            if (slash > 0) mount.makeDirectory(path.substring(0, slash));
            try (var channel = mount.openFile(path, Set.of(java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            written.add(path);
        }

        var out = new JsonObject();
        out.addProperty("id", computer.getID());
        out.addProperty("name", name);
        out.add("written", strings(written));
        out.add("deleted", strings(deleted));
        var check = new JsonObject();
        out.addProperty("identical", describe(check, snap, scan(mount)));
        if (!out.get("identical").getAsBoolean()) out.add("still_different", check);
        return out;
    }

    static JsonObject list(Path home, int id) throws IOException {
        var out = new JsonObject();
        out.addProperty("id", id);
        var names = new JsonArray();
        var root = home.resolve("snapshots").resolve("computer-" + id);
        if (Files.isDirectory(root)) {
            try (var dirs = Files.list(root)) {
                for (var d : (Iterable<Path>) dirs.sorted()::iterator) {
                    if (!Files.exists(d.resolve(MANIFEST))) continue;
                    var m = manifest(d);
                    var entry = new JsonObject();
                    entry.addProperty("name", d.getFileName().toString());
                    entry.addProperty("saved", m.saved());
                    entry.addProperty("files", m.files().size());
                    names.add(entry);
                }
            }
        }
        out.add("snapshots", names);
        return out;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }

    private static JsonArray strings(Iterable<String> values) {
        var array = new JsonArray();
        for (var v : values) array.add(v);
        return array;
    }

    static List<String> ops() {
        return List.of("save", "diff", "restore", "list");
    }
}

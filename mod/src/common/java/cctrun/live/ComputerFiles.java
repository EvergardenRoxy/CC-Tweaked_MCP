// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Access to a computer's own files (its save directory, the same files Lua sees under {@code /}, without disk drives)
 * and, read-only, to {@code /rom} (CC: Tweaked's ROM with data pack additions, as the computer sees it), read on the
 * Java side so nothing runs on the computer.
 */
final class ComputerFiles {
    static final int MAX_READ = 1024 * 1024;
    static final int DEFAULT_READ = 64 * 1024;

    private ComputerFiles() {
    }

    /** See {@link ComputerPaths#sanitise(String)}: rejects ".." and Windows drive letters before any mount call. */
    static String sanitise(String path) {
        return ComputerPaths.sanitise(path);
    }

    private static boolean isRom(String path) {
        return path.equals("rom") || path.startsWith("rom/");
    }

    /** The mount that holds {@code path} and the path inside it: /rom is the ROM, anything else the computer's drive. */
    private record Resolved(Mount mount, String inner) {
    }

    private static Resolved resolve(ServerComputer computer, String path) {
        if (!isRom(path)) return new Resolved(computer.createRootMount(), path);
        var rom = ComputerCraftAPI.createResourceMount(computer.getLevel().getServer(), "computercraft", "lua/rom");
        if (rom == null) throw new IllegalArgumentException("/rom is not available");
        return new Resolved(rom, path.equals("rom") ? "" : path.substring(4));
    }

    static JsonObject list(ServerComputer computer, String rawPath) throws IOException {
        var path = sanitise(rawPath);
        var r = resolve(computer, path);
        var mount = r.mount();
        if (!mount.exists(r.inner())) throw new IllegalArgumentException("/" + path + ": no such file or directory");
        if (!mount.isDirectory(r.inner())) throw new IllegalArgumentException("/" + path + ": not a directory");
        var names = new ArrayList<String>();
        mount.list(r.inner(), names);
        // As fs.list("/") in Lua: the ROM is mounted at /rom.
        if (path.isEmpty() && !names.contains("rom")) names.add("rom");
        names.sort(String::compareTo);
        var entries = new JsonArray();
        for (var name : names) {
            var entry = new JsonObject();
            entry.addProperty("name", name);
            if (path.isEmpty() && name.equals("rom")) {
                entry.addProperty("dir", true);
                entry.addProperty("read_only", true);
                entries.add(entry);
                continue;
            }
            var child = r.inner().isEmpty() ? name : r.inner() + "/" + name;
            var dir = mount.isDirectory(child);
            entry.addProperty("dir", dir);
            if (!dir) entry.addProperty("size", mount.getSize(child));
            entries.add(entry);
        }
        var out = base(computer, path);
        out.add("entries", entries);
        return out;
    }

    static JsonObject stat(ServerComputer computer, String rawPath) throws IOException {
        var path = sanitise(rawPath);
        var r = resolve(computer, path);
        var out = base(computer, path);
        var exists = r.mount().exists(r.inner());
        out.addProperty("exists", exists);
        if (exists) {
            var dir = r.mount().isDirectory(r.inner());
            out.addProperty("dir", dir);
            if (!dir) out.addProperty("size", r.mount().getSize(r.inner()));
            if (isRom(path)) out.addProperty("read_only", true);
        }
        return out;
    }

    /** Text if the bytes are valid UTF-8, else ISO-8859-1 (CC's own byte-per-character view). */
    static JsonObject read(ServerComputer computer, String rawPath, long offset, int length) throws IOException {
        var path = sanitise(rawPath);
        var r = resolve(computer, path);
        var mount = r.mount();
        if (!mount.exists(r.inner())) throw new IllegalArgumentException("/" + path + ": no such file");
        if (mount.isDirectory(r.inner())) throw new IllegalArgumentException("/" + path + ": is a directory");
        var size = mount.getSize(r.inner());
        var buffer = ByteBuffer.allocate((int) Math.max(0, Math.min(length, size - Math.min(offset, size))));
        try (var channel = mount.openForRead(r.inner())) {
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading
            }
        }
        buffer.flip();
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        var out = base(computer, path);
        out.addProperty("size", size);
        out.addProperty("offset", offset);
        out.addProperty("length", bytes.length);
        out.addProperty("eof", offset + bytes.length >= size);
        try {
            var text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            out.addProperty("encoding", "utf-8");
            out.addProperty("text", text);
        } catch (CharacterCodingException e) {
            out.addProperty("encoding", "iso-8859-1");
            out.addProperty("text", new String(bytes, StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    /** The directory cct-run uses for its own temporary files on the computer (cct_live_lua). */
    static final String TEMP_DIR = ".cctlive";

    /** Deletes a file or directory, only inside {@link #TEMP_DIR}: other deletions stay with the player. */
    static JsonObject delete(ServerComputer computer, String rawPath) throws IOException {
        var path = sanitise(rawPath);
        if (!path.equals(TEMP_DIR) && !path.startsWith(TEMP_DIR + "/")) {
            throw new IllegalArgumentException("only files under /" + TEMP_DIR + " can be deleted through cct-live");
        }
        var mount = computer.createRootMount();
        var existed = mount.exists(path);
        if (existed) mount.delete(path);
        var out = base(computer, path);
        out.addProperty("deleted", existed);
        return out;
    }

    /** Text if the bytes are valid UTF-8, else ISO-8859-1, as in read. */
    static void putText(JsonObject out, byte[] bytes) {
        try {
            var text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            out.addProperty("encoding", "utf-8");
            out.addProperty("text", text);
        } catch (CharacterCodingException e) {
            out.addProperty("encoding", "iso-8859-1");
            out.addProperty("text", new String(bytes, StandardCharsets.ISO_8859_1));
        }
    }

    /** Writes text to a file (parent directories are created); the mount enforces the computer's disk space. */
    static JsonObject write(ServerComputer computer, String rawPath, String text, String encoding, boolean append) throws IOException {
        var path = sanitise(rawPath);
        if (path.isEmpty()) throw new IllegalArgumentException("path must name a file");
        if (path.equals("rom") || path.startsWith("rom/")) throw new IllegalArgumentException("/rom is read-only");
        var bytes = text.getBytes(encoding.equals("utf-8") ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        if (bytes.length > MAX_READ) throw new IllegalArgumentException("at most " + MAX_READ + " bytes per write");
        var mount = computer.createRootMount();
        if (mount.exists(path) && mount.isDirectory(path)) throw new IllegalArgumentException("/" + path + ": is a directory");
        var slash = path.lastIndexOf('/');
        if (slash > 0) mount.makeDirectory(path.substring(0, slash));
        var options = append
            ? java.util.Set.<java.nio.file.OpenOption>of(java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            : java.util.Set.<java.nio.file.OpenOption>of(java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        try (var channel = mount.openFile(path, options)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        var out = base(computer, path);
        out.addProperty("written", bytes.length);
        out.addProperty("size", mount.getSize(path));
        out.addProperty("append", append);
        return out;
    }

    private static JsonObject base(ServerComputer computer, String path) throws IOException {
        var mount = computer.createRootMount();
        var out = new JsonObject();
        out.addProperty("id", computer.getID());
        out.addProperty("path", "/" + path);
        out.addProperty("free_space", mount.getRemainingSpace());
        out.addProperty("capacity", mount.getCapacity());
        return out;
    }
}

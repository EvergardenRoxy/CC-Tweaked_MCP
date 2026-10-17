// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Read-only access to a computer's own files (its save directory, the same files Lua sees under {@code /}, without
 * /rom and disk drives), read on the Java side so nothing runs on the computer.
 */
final class ComputerFiles {
    static final int MAX_READ = 1024 * 1024;
    static final int DEFAULT_READ = 64 * 1024;

    private ComputerFiles() {
    }

    /** "/a/./b/" -> "a/b"; rejects ".." so a request cannot leave the computer's directory. */
    static String sanitise(String path) {
        var out = new ArrayList<String>();
        for (var part : (path == null ? "" : path).replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IllegalArgumentException("'..' is not allowed in paths");
            out.add(part);
        }
        return String.join("/", out);
    }

    static JsonObject list(ServerComputer computer, String rawPath) throws IOException {
        var path = sanitise(rawPath);
        var mount = computer.createRootMount();
        if (!mount.exists(path)) throw new IllegalArgumentException("/" + path + ": no such file or directory");
        if (!mount.isDirectory(path)) throw new IllegalArgumentException("/" + path + ": not a directory");
        var names = new ArrayList<String>();
        mount.list(path, names);
        names.sort(String::compareTo);
        var entries = new JsonArray();
        for (var name : names) {
            var child = path.isEmpty() ? name : path + "/" + name;
            var entry = new JsonObject();
            entry.addProperty("name", name);
            var dir = mount.isDirectory(child);
            entry.addProperty("dir", dir);
            if (!dir) entry.addProperty("size", mount.getSize(child));
            entries.add(entry);
        }
        var out = base(computer, mount, path);
        out.add("entries", entries);
        return out;
    }

    static JsonObject stat(ServerComputer computer, String rawPath) throws IOException {
        var path = sanitise(rawPath);
        var mount = computer.createRootMount();
        var out = base(computer, mount, path);
        var exists = mount.exists(path);
        out.addProperty("exists", exists);
        if (exists) {
            var dir = mount.isDirectory(path);
            out.addProperty("dir", dir);
            if (!dir) out.addProperty("size", mount.getSize(path));
        }
        return out;
    }

    /** Text if the bytes are valid UTF-8, else ISO-8859-1 (CC's own byte-per-character view). */
    static JsonObject read(ServerComputer computer, String rawPath, long offset, int length) throws IOException {
        var path = sanitise(rawPath);
        var mount = computer.createRootMount();
        if (!mount.exists(path)) throw new IllegalArgumentException("/" + path + ": no such file");
        if (mount.isDirectory(path)) throw new IllegalArgumentException("/" + path + ": is a directory");
        var size = mount.getSize(path);
        var buffer = ByteBuffer.allocate((int) Math.max(0, Math.min(length, size - Math.min(offset, size))));
        try (var channel = mount.openForRead(path)) {
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading
            }
        }
        buffer.flip();
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        var out = base(computer, mount, path);
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
        var out = base(computer, mount, path);
        out.addProperty("written", bytes.length);
        out.addProperty("size", mount.getSize(path));
        out.addProperty("append", append);
        return out;
    }

    private static JsonObject base(ServerComputer computer, WritableMount mount, String path) throws IOException {
        var out = new JsonObject();
        out.addProperty("id", computer.getID());
        out.addProperty("path", "/" + path);
        out.addProperty("free_space", mount.getRemainingSpace());
        out.addProperty("capacity", mount.getCapacity());
        return out;
    }
}

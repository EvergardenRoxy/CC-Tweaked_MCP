// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;

/** Two bounded trace segments. Sequence/index updates commit only after a complete write. */
final class ComputerTrace {
    static final String SCHEMA = "cct-live/trace@0";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    @FunctionalInterface interface Opener { SeekableByteChannel open(Path path) throws IOException; }
    private final Path file, oldFile;
    private final LongSupplier tick;
    private final DoubleSupplier millis;
    private final long maxBytes;
    private final Opener opener;
    private final LongSupplier nanos;
    private SeekableByteChannel out;
    private long seq, bytes, firstSeq = 1, currentFirstSeq = 1;
    private int rotations;
    private boolean closed;
    private final ArrayList<String> kinds = new ArrayList<>();
    private final Map<String, JsonObject> latest = new HashMap<>();
    private static final Set<String> KEEP_LATEST = Set.of("printed_error", "vm_error", "detach", "attach");
    private long dropped, retryAt;
    private String lastError;

    ComputerTrace(LongSupplier tick, DoubleSupplier millis, Path file, long maxBytes) throws IOException {
        this(tick, millis, file, maxBytes, p -> Files.newByteChannel(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE));
    }
    ComputerTrace(LongSupplier tick, DoubleSupplier millis, Path file, long maxBytes, Opener opener) throws IOException {
        this(tick,millis,file,maxBytes,opener,System::nanoTime);
    }
    ComputerTrace(LongSupplier tick, DoubleSupplier millis, Path file, long maxBytes, Opener opener, LongSupplier nanos) throws IOException {
        this.nanos = nanos;
        if (maxBytes < 512) throw new IllegalArgumentException("trace budget must be at least 512 bytes");
        this.tick = tick; this.millis = millis; this.file = file; this.maxBytes = maxBytes; this.opener = opener;
        oldFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jsonl$", "") + ".old.jsonl");
        if (Files.exists(oldFile)) throw new FileAlreadyExistsException(oldFile.toString());
        Files.createFile(file);
        out = opener.open(file);
        var header = new JsonObject(); header.addProperty("schema", SCHEMA);
        record("trace_start", header);
        if (seq == 0) throw new IOException("could not start trace: " + lastError);
    }
    Path file() { return file; }
    synchronized List<Path> segments() { return rotations > 0 && Files.exists(oldFile) ? List.of(oldFile, file) : List.of(file); }
    synchronized int rotations() { return rotations; }
    synchronized long firstSeq() { return firstSeq; }
    synchronized long lastSeq() { return seq; }
    synchronized int retainedRecords() { return kinds.size(); }
    synchronized JsonObject health() {
        var health = new JsonObject();
        health.addProperty("dropped_records", dropped);
        health.addProperty("writable", !closed && out != null);
        if (lastError != null) health.addProperty("last_error", lastError);
        return health;
    }

    /** Copy files while holding the writer monitor, then stream immutable disk snapshots. This avoids both
     * Windows rename/open-handle races and copying a whole trace into the Java heap. Callers MUST close. */
    synchronized Stream<String> lines() throws IOException {
        var snapshots = Files.createTempDirectory(file.getParent(), ".trace-read-");
        Stream<String> stream = Stream.empty();
        try {
            int n = 0;
            for (var segment : segments()) {
                if (!Files.exists(segment)) continue;
                var copy = snapshots.resolve(Integer.toString(n++));
                Files.copy(segment, copy);
                stream = Stream.concat(stream, Files.lines(copy, StandardCharsets.UTF_8));
            }
            return stream.onClose(() -> {
                try { SnapshotStore.deleteTree(snapshots); }
                catch (IOException e) { System.getLogger("cct-live").log(System.Logger.Level.WARNING, "Could not remove trace reader snapshot: " + snapshots); }
            });
        } catch (IOException | RuntimeException e) {
            stream.close(); SnapshotStore.deleteTree(snapshots); throw e;
        }
    }

    /** A status match does not need to copy or parse the entire trace. Protected against rotation by the monitor. */
    synchronized JsonObject recordAt(long wanted) throws IOException {
        if (wanted < firstSeq || wanted > seq) return null;
        var prefix = "{\"seq\":" + wanted + ",";
        for (var segment : segments()) {
            if (!Files.exists(segment)) continue;
            try (var reader = Files.newBufferedReader(segment, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith(prefix)) continue;
                    try { return TraceRecords.parse(line); }
                    catch (RuntimeException ignored) { }
                }
            }
        }
        return null;
    }

    void record(String kind, JsonObject data) { recordAt(tick.getAsLong(), millis.getAsDouble(), kind, data); }
    synchronized void recordAt(long atTick, double ms, String kind, JsonObject data) {
        if (closed) return;
        if (out == null && nanos.getAsLong() < retryAt) { dropped++; return; }
        try {
            if (out == null) {
                out = opener.open(file); out.truncate(bytes); out.position(bytes);
                var recovery = new JsonObject(); recovery.addProperty("dropped_records", dropped);
                var recoveryLine = line(atTick, ms, "trace_error", recovery);
                if (bytes + encode(recoveryLine).length > maxBytes / 2) rotate(atTick, ms);
                append(atTick, ms, "trace_error", recovery);
            }
            var line = line(atTick, ms, kind, data);
            var encoded = encode(line);
            // A single record larger than one segment cannot respect the configured total quota.
            if (encoded.length > maxBytes / 2) { dropped++; lastError = "record exceeds trace segment budget"; return; }
            if (bytes > 0 && bytes + encoded.length > maxBytes / 2) {
                rotate(atTick, ms);
                line = line(atTick, ms, kind, data); encoded = encode(line);
            }
            if (bytes + encoded.length > maxBytes / 2) { dropped++; lastError = "record plus rotation header exceeds segment budget"; return; }
            write(line, encoded);
        } catch (IOException e) {
            dropped++; lastError = "trace I/O failure: " + e.getClass().getSimpleName();
            retryAt = nanos.getAsLong() + 1_000_000_000L;
            closeWriter();
            System.getLogger("cct-live").log(System.Logger.Level.WARNING, "Trace write failed; recording will retry: " + file);
        }
    }
    private JsonObject line(long atTick, double ms, String kind, JsonObject data) {
        var line = new JsonObject();
        line.addProperty("seq", seq + 1); line.addProperty("tick", atTick); line.addProperty("ms", ms); line.addProperty("kind", kind);
        for (var e : data.entrySet()) if (!Set.of("seq", "tick", "ms", "kind").contains(e.getKey())) line.add(e.getKey(), e.getValue());
        return line;
    }
    private static byte[] encode(JsonObject line) { return (GSON.toJson(line) + "\n").getBytes(StandardCharsets.UTF_8); }
    private void append(long atTick, double ms, String kind, JsonObject data) throws IOException {
        var line = line(atTick, ms, kind, data); write(line, encode(line));
    }
    private void write(JsonObject line, byte[] encoded) throws IOException {
        long start = bytes;
        try {
            var buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) out.write(buffer);
        } catch (IOException e) {
            try { out.truncate(start); out.position(start); } catch (IOException rollback) { e.addSuppressed(rollback); }
            throw e;
        }
        bytes += encoded.length; seq++;
        String kind = line.get("kind").getAsString(); kinds.add(kind);
        if (KEEP_LATEST.contains(kind)) latest.put(kind, line);
    }
    private void rotate(long atTick, double ms) throws IOException {
        closeWriter();
        Files.move(file, oldFile, StandardCopyOption.REPLACE_EXISTING);
        firstSeq = currentFirstSeq; currentFirstSeq = seq + 1;
        // Only retained records are indexed; all sequence arithmetic remains long until checked list indexing.
        int discard = Math.toIntExact(firstSeq - (seq - kinds.size() + 1));
        if (discard > 0) kinds.subList(0, discard).clear();
        bytes = 0; rotations++;
        out = opener.open(file); out.truncate(0);
        var data = new JsonObject(); data.addProperty("rotations", rotations); data.addProperty("first_seq", firstSeq); data.addProperty("max_bytes", maxBytes);
        append(atTick, ms, "trace_rotated", data);
    }
    synchronized Map<String, Integer> countsAfter(long after) {
        var counts = new TreeMap<String, Integer>();
        long start = Math.max(0, Math.min((long) kinds.size(), after - firstSeq + 1));
        for (int i = (int) start; i < kinds.size(); i++) counts.merge(kinds.get(i), 1, Integer::sum);
        return counts;
    }
    synchronized long firstAfter(long after, Set<String> wanted) {
        long start = Math.max(0, Math.min((long) kinds.size(), after - firstSeq + 1));
        for (int i = (int) start; i < kinds.size(); i++) if (wanted.contains(kinds.get(i))) return firstSeq + i;
        return -1;
    }
    synchronized JsonObject latest(String kind) { var value = latest.get(kind); return value == null ? null : value.deepCopy(); }
    synchronized void flush() { /* each complete record is already passed to the OS; no buffered Java tail */ }
    private void closeWriter() { if (out != null) { try { out.close(); } catch (IOException ignored) { } finally { out = null; } } }
    synchronized void close() { closed = true; closeWriter(); }

    static final int MAX_BODY = 4 * 1024 * 1024;
    static final long MAX_BODIES = 64L * 1024 * 1024;
    private int bodies;
    private long bodyBytes;
    synchronized int saveBody(byte[] data) {
        if (closed || bodyBytes + Math.min(data.length, MAX_BODY) > MAX_BODIES) return -1;
        int n = ++bodies;
        try {
            Files.write(bodyFile(n), data.length <= MAX_BODY ? data : Arrays.copyOf(data, MAX_BODY));
            bodyBytes += Math.min(data.length, MAX_BODY); return n;
        } catch (IOException e) { return -1; }
    }
    Path bodyFile(int n) {
        return file.resolveSibling(file.getFileName().toString().replaceFirst("\\.jsonl$", "") + "-body-" + n + ".bin");
    }
}

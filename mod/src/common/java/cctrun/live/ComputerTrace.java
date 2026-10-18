// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One computer's trace for one server session: JSON Lines in the cct-run trace format (common fields
 * {@code seq}, {@code tick}, {@code ms}, {@code kind}; see handoff.md TRACE_FORMAT), schema {@code cct-live/trace@0}.
 * {@code tick} counts server ticks since the session started.
 * <p>
 * Written from computer threads (events, peripheral calls) and the server thread (polling), so writes are
 * synchronised. Keeps the newest records within the configured size: the file is split into two segments of half
 * that size; when the current one is full it becomes {@code <name>.old.jsonl} (replacing the older one) and a new
 * current file starts with a {@code trace_rotated} record. Readers go through {@link #segments()}.
 */
final class ComputerTrace {
    static final String SCHEMA = "cct-live/trace@0";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final Path file;
    private final Path oldFile;
    private BufferedWriter out;
    private final LiveSession session;
    private final long maxBytes;
    private final AtomicLong callIds = new AtomicLong();
    private long seq;
    /** kinds.get(n - 1) is the kind of record seq n: lets status and wait answer without reading the file. */
    private final java.util.ArrayList<String> kinds = new java.util.ArrayList<>();
    /** The latest record of a few kinds an agent asks about all the time. */
    private final java.util.Map<String, JsonObject> latest = new java.util.HashMap<>();
    private static final java.util.Set<String> KEEP_LATEST = java.util.Set.of("printed_error", "vm_error", "detach", "attach");
    private long bytes;
    private int rotations;
    /** First seq still on disk (in the old segment if there is one). */
    private long firstSeq = 1;
    private long currentFirstSeq = 1;
    private boolean closed;

    ComputerTrace(LiveSession session, Path file, long maxBytes) throws IOException {
        this.session = session;
        this.file = file;
        this.maxBytes = maxBytes;
        var name = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
        this.oldFile = file.resolveSibling(name + ".old.jsonl");
        Files.deleteIfExists(oldFile);
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        var header = new JsonObject();
        header.addProperty("schema", SCHEMA);
        record("trace_start", header);
    }

    Path file() {
        return file;
    }

    /** The trace files to read, oldest first: the old segment (if the trace was rotated) and the current one. */
    synchronized java.util.List<Path> segments() {
        return rotations > 0 && Files.exists(oldFile) ? java.util.List.of(oldFile, file) : java.util.List.of(file);
    }

    /** All records on disk, oldest first; the caller closes the stream. */
    java.util.stream.Stream<String> lines() throws IOException {
        flush();
        java.util.stream.Stream<String> all = java.util.stream.Stream.empty();
        for (var segment : segments()) {
            java.util.stream.Stream<String> next;
            try {
                next = Files.lines(segment, StandardCharsets.UTF_8);
            } catch (java.nio.file.NoSuchFileException e) {
                continue; // rotated away between segments() and here
            }
            all = java.util.stream.Stream.concat(all, next);
        }
        return all;
    }

    synchronized int rotations() {
        return rotations;
    }

    synchronized long firstSeq() {
        return firstSeq;
    }

    long nextCallId() {
        return callIds.incrementAndGet();
    }

    void record(String kind, JsonObject data) {
        recordAt(session.tickCount(), session.millis(), kind, data);
    }

    /** Write a record observed earlier (buffered until the computer was identified). */
    synchronized void recordAt(long tick, double ms, String kind, JsonObject data) {
        if (closed) return;
        try {
            // Rough size of the record (seq, tick and ms add a few dozen characters): rotate before it is numbered.
            var estimate = Utf8.length(GSON.toJson(data)) + Utf8.length(kind) + 64;
            if (bytes > 0 && bytes + estimate > maxBytes / 2) rotate(tick, ms);
            write(line(tick, ms, kind, data));
        } catch (IOException e) {
            CctLive.LOG.warn("Could not write {}; tracing for this computer stops", file, e);
            closed = true;
        }
    }

    /** A numbered record; seq comes first (LiveHttp finds records by their "{\"seq\":N," prefix). */
    private JsonObject line(long tick, double ms, String kind, JsonObject data) {
        var line = new JsonObject();
        line.addProperty("seq", ++seq);
        line.addProperty("tick", tick);
        line.addProperty("ms", ms);
        line.addProperty("kind", kind);
        for (var entry : data.entrySet()) line.add(entry.getKey(), entry.getValue());
        kinds.add(kind.intern());
        if (KEEP_LATEST.contains(kind)) latest.put(kind, line);
        return line;
    }

    private void write(JsonObject line) throws IOException {
        var text = GSON.toJson(line);
        out.write(text);
        out.write('\n');
        // The trace is UTF-8 and max_bytes is a byte budget: count bytes, not chars.
        bytes += Utf8.length(text) + 1;
    }

    /**
     * The current segment is full: it becomes the old one (the previous old one is dropped), and a new current file
     * starts with a trace_rotated record.
     */
    private void rotate(long tick, double ms) throws IOException {
        out.close();
        Files.move(file, oldFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        bytes = 0;
        rotations++;
        firstSeq = currentFirstSeq;
        currentFirstSeq = seq + 1;
        var data = new JsonObject();
        data.addProperty("rotations", rotations);
        data.addProperty("first_seq", firstSeq);
        data.addProperty("max_bytes", maxBytes);
        write(line(tick, ms, "trace_rotated", data));
    }

    static final int MAX_BODY = 4 * 1024 * 1024;
    static final long MAX_BODIES = 64L * 1024 * 1024;
    private int bodies;
    private long bodyBytes;

    /**
     * Keeps a full HTTP response body next to the trace ({@code <trace>-body-<n>.bin}, at most 4 MB each and 64 MB
     * per trace) so it can be read back later; returns n, or -1 if the budget is used up.
     */
    synchronized int saveBody(byte[] data) {
        if (closed || bodyBytes + Math.min(data.length, MAX_BODY) > MAX_BODIES) return -1;
        var n = ++bodies;
        try {
            Files.write(bodyFile(n), data.length <= MAX_BODY ? data : java.util.Arrays.copyOf(data, MAX_BODY));
            bodyBytes += Math.min(data.length, MAX_BODY);
            return n;
        } catch (IOException e) {
            CctLive.LOG.warn("Could not save a response body next to {}", file, e);
            return -1;
        }
    }

    Path bodyFile(int n) {
        var name = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
        return file.resolveSibling(name + "-body-" + n + ".bin");
    }

    synchronized long lastSeq() {
        return seq;
    }

    /** Records after seq {@code after}, counted by kind. */
    synchronized java.util.Map<String, Integer> countsAfter(long after) {
        var out = new java.util.TreeMap<String, Integer>();
        for (var i = (int) Math.max(0, after); i < kinds.size(); i++) out.merge(kinds.get(i), 1, Integer::sum);
        return out;
    }

    /** seq of the first record after {@code after} whose kind is one of {@code wanted}, or -1. */
    synchronized long firstAfter(long after, java.util.Set<String> wanted) {
        for (var i = (int) Math.max(0, after); i < kinds.size(); i++) if (wanted.contains(kinds.get(i))) return i + 1;
        return -1;
    }

    synchronized JsonObject latest(String kind) {
        var record = latest.get(kind);
        return record == null ? null : record.deepCopy();
    }

    synchronized void flush() {
        if (closed) return;
        try {
            out.flush();
        } catch (IOException e) {
            CctLive.LOG.warn("Could not flush {}", file, e);
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            out.close();
        } catch (IOException e) {
            CctLive.LOG.warn("Could not close {}", file, e);
        }
    }
}

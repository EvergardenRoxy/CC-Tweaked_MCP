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
 * synchronised. Stops at the configured size with one {@code truncated} record.
 */
final class ComputerTrace {
    static final String SCHEMA = "cct-live/trace@0";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final Path file;
    private final BufferedWriter out;
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
    private boolean truncated;
    private boolean closed;

    ComputerTrace(LiveSession session, Path file, long maxBytes) throws IOException {
        this.session = session;
        this.file = file;
        this.maxBytes = maxBytes;
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        var header = new JsonObject();
        header.addProperty("schema", SCHEMA);
        record("trace_start", header);
    }

    Path file() {
        return file;
    }

    long nextCallId() {
        return callIds.incrementAndGet();
    }

    void record(String kind, JsonObject data) {
        recordAt(session.tickCount(), session.millis(), kind, data);
    }

    /** Write a record observed earlier (buffered until the computer was identified). */
    synchronized void recordAt(long tick, double ms, String kind, JsonObject data) {
        if (closed || truncated) return;
        var line = new JsonObject();
        line.addProperty("seq", ++seq);
        line.addProperty("tick", tick);
        line.addProperty("ms", ms);
        line.addProperty("kind", kind);
        for (var entry : data.entrySet()) line.add(entry.getKey(), entry.getValue());
        kinds.add(kind.intern());
        if (KEEP_LATEST.contains(kind)) latest.put(kind, line);
        var text = GSON.toJson(line);
        try {
            if (bytes + text.length() + 1 > maxBytes) {
                truncated = true;
                var cut = new JsonObject();
                cut.addProperty("seq", ++seq);
                kinds.add("truncated");
                cut.addProperty("tick", tick);
                cut.addProperty("ms", ms);
                cut.addProperty("kind", "truncated");
                cut.addProperty("max_bytes", maxBytes);
                out.write(GSON.toJson(cut));
                out.write('\n');
                out.flush();
                return;
            }
            out.write(text);
            out.write('\n');
            bytes += text.length() + 1;
        } catch (IOException e) {
            CctLive.LOG.warn("Could not write {}; tracing for this computer stops", file, e);
            closed = true;
        }
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

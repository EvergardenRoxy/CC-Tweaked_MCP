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
        var text = GSON.toJson(line);
        try {
            if (bytes + text.length() + 1 > maxBytes) {
                truncated = true;
                var cut = new JsonObject();
                cut.addProperty("seq", ++seq);
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

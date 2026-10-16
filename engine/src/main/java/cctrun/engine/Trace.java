// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only JSON Lines log of everything observed during one run. Every record carries
 * {@code seq}, {@code tick}, {@code ms} and {@code kind}; see handoff.md (TRACE_FORMAT) for the kinds.
 * <p>
 * Records are written from the computer thread, the main (server) thread and the tick loop, so all
 * writes are synchronised.
 */
public final class Trace implements Closeable {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int MAX_NOTES = 10;

    private final BufferedWriter out;
    private final Clock clock;
    private final AtomicLong callIds = new AtomicLong();
    private final Map<String, Integer> counts = new TreeMap<>();
    private final JsonArray notes = new JsonArray();
    private long seq;
    private boolean closed;

    public Trace(Path file, Clock clock) throws IOException {
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        this.clock = clock;
        var header = new JsonObject();
        header.addProperty("schema", Versions.TRACE_SCHEMA);
        record("trace_start", header);
    }

    public Clock clock() {
        return clock;
    }

    /** Id pairing a {@code peripheral_call} with its {@code peripheral_return}. */
    public long nextCallId() {
        return callIds.incrementAndGet();
    }

    /** Write one record. {@code data} is copied after the common fields. */
    public synchronized void record(String kind, JsonObject data) {
        if (closed) return;
        var line = new JsonObject();
        line.addProperty("seq", ++seq);
        line.addProperty("tick", clock.tick());
        line.addProperty("ms", clock.millis());
        line.addProperty("kind", kind);
        for (var entry : data.entrySet()) line.add(entry.getKey(), entry.getValue());
        counts.merge(kind, 1, Integer::sum);
        try {
            out.write(GSON.toJson(line));
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Record something the summary should point out (peripheral errors and the like). */
    public synchronized void note(String kind, JsonObject data) {
        record(kind, data);
        if (notes.size() < MAX_NOTES) {
            var note = data.deepCopy();
            note.addProperty("seq", seq);
            note.addProperty("tick", clock.tick());
            note.addProperty("kind", kind);
            notes.add(note);
        }
    }

    synchronized Map<String, Integer> counts() {
        return new TreeMap<>(counts);
    }

    synchronized JsonArray notes() {
        return notes.deepCopy();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        out.close();
    }
}

// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The JSON Lines log itself: durability while open and the note quota. */
class TraceTest {
    @TempDir
    Path dir;

    /** A killed run still has every record written so far, so nothing may sit in the writer's buffer. */
    @Test
    void recordsAreReadableBeforeTheTraceIsClosed() throws Exception {
        var file = dir.resolve("trace.jsonl");
        var trace = new Trace(file, new Clock());
        try {
            var event = new JsonObject();
            event.addProperty("name", "chat");
            trace.record("event", event);
            var lines = Files.readAllLines(file);
            assertEquals(2, lines.size(), lines.toString()); // trace_start + event
            assertEquals("event", Trace.GSON.fromJson(lines.get(1), JsonObject.class).get("kind").getAsString());
        } finally {
            trace.close();
        }
    }

    @Test
    void keepsTheFirstTenNotesAndCountsTheRest() throws Exception {
        try (var trace = new Trace(dir.resolve("trace.jsonl"), new Clock())) {
            for (int i = 0; i < 12; i++) {
                var data = new JsonObject();
                data.addProperty("n", i);
                trace.note("peripheral_return", data);
            }
            assertEquals(10, trace.notes().size());
            assertEquals(2, trace.droppedNotes());
            assertEquals(12, trace.counts().get("peripheral_return"));
        }
    }
}

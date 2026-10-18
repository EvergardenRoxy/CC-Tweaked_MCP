// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.List;

/** Recording mode: the most recent records of one computer, kept in memory until an AI attaches. */
final class Ring {
    record Entry(long tick, double ms, String kind, JsonObject data) {
    }

    private final int capacity;
    private final ArrayDeque<Entry> entries;
    private long dropped;

    Ring(int capacity) {
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(capacity);
    }

    synchronized void add(long tick, double ms, String kind, JsonObject data) {
        if (entries.size() == capacity) {
            entries.removeFirst();
            dropped++;
        }
        entries.addLast(new Entry(tick, ms, kind, data));
    }

    /** Takes everything recorded so far (oldest first); records that fell out of the ring are counted. */
    synchronized List<Entry> drain(long[] droppedOut) {
        var out = List.copyOf(entries);
        entries.clear();
        droppedOut[0] = dropped;
        dropped = 0;
        return out;
    }
}

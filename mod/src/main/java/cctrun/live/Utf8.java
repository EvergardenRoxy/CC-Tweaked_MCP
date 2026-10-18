// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import java.nio.charset.StandardCharsets;

/** Byte lengths for limits that are documented in bytes. No Minecraft imports, so it can be unit tested. */
final class Utf8 {
    private Utf8() {
    }

    /** The number of UTF-8 bytes {@code text} occupies (the trace file is UTF-8; a char count undercounts it). */
    static int length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}

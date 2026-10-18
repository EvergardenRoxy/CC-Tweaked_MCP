// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8Test {
    @Test
    void countsBytesNotChars() {
        assertEquals(0, Utf8.length(null));
        assertEquals(0, Utf8.length(""));
        assertEquals(3, Utf8.length("abc"));
        // A Chinese character is three bytes and one Java char.
        assertEquals(3, Utf8.length("\u4e2d"));
        assertEquals(6, Utf8.length("\u4e2d\u6587"));
        // An emoji is one code point but two Java chars and four bytes.
        assertEquals(4, Utf8.length("\uD83D\uDE00"));
    }
}

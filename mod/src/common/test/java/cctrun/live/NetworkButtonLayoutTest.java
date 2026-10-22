// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkButtonLayoutTest {
    // CC:T 1.120.2 sidebar column: guiLeft .. guiLeft + 17; buttons at x + 4, terminal top + 5 and + 21, 12px tall.
    private static final int SIDEBAR_WIDTH = 17, SIDEBAR_BUTTONS_BOTTOM = 5 + 16 + 12;

    @Test void sitsLeftOfTheGuiWithItsTopOnTheTerminalTop() {
        // Advanced computer (51x19) at GUI scale 2 in a 960x540 window: guiLeft ≈ 147, terminal top = guiTop + 12.
        var p = NetworkButtonLayout.place(147, 92);
        assertEquals(147 - NetworkButtonLayout.SIZE - NetworkButtonLayout.GAP, p.x());
        assertEquals(92, p.y());
        assertTrue(p.x() + NetworkButtonLayout.SIZE < 147, "must not overlap the GUI or CC's sidebar");
    }

    @Test void withoutRoomOnTheLeftItGoesBelowCcSidebarButtonsInsideTheWindow() {
        for (int guiLeft : new int[]{0, 5, 17}) {
            var p = NetworkButtonLayout.place(guiLeft, 12);
            assertTrue(p.x() >= 0);
            assertTrue(p.x() + NetworkButtonLayout.SIZE <= guiLeft + SIDEBAR_WIDTH, "stays in the sidebar column");
            assertTrue(p.y() >= 12 + SIDEBAR_BUTTONS_BOTTOM, "below power and terminate");
        }
    }
}

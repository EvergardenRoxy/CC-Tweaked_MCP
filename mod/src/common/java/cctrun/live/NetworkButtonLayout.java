// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

/**
 * Where the AI authorization icon goes on a CC:T computer, turtle or pocket computer screen: just left of the
 * whole GUI (CC's power/terminate sidebar is its leftmost column), top flush with the terminal's top edge. When the
 * window leaves no room there, it goes into the sidebar column below CC's two buttons, which start 5px below the
 * terminal top and are 12px tall at a 16px pitch.
 */
final class NetworkButtonLayout {
    static final int SIZE = 16;
    static final int GAP = 2;
    /** Below CC's terminate button (terminal top + 5 + 16 + 12) with a small margin. */
    static final int SIDEBAR_OFFSET = 36;

    record Position(int x, int y) {}

    static Position place(int guiLeft, int terminalTop) {
        if (guiLeft - SIZE - GAP >= 0) return new Position(guiLeft - SIZE - GAP, terminalTop);
        return new Position(Math.max(0, guiLeft), terminalTop + SIDEBAR_OFFSET);
    }

    private NetworkButtonLayout() {}
}

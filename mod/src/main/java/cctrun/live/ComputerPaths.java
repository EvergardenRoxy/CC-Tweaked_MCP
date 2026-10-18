// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import java.util.ArrayList;

/**
 * Path rules for the files/HTTP endpoints. Kept in its own class (no Minecraft/CC imports) so they can be unit
 * tested without a game: {@link ComputerFiles} calls {@link #sanitise(String)} before every mount operation.
 */
final class ComputerPaths {
    private ComputerPaths() {
    }

    /**
     * "/a/./b/" -> "a/b". Rejects anything that could address a path outside the computer's directory: "..", and
     * ':' (a Windows drive letter such as "C:") because the mount resolves paths with {@code Path.resolve}, which
     * returns an absolute argument unchanged (so "C:/Windows/win.ini" escaped the mount; see the cct-live review B0).
     * Callers must run this before any mount call.
     */
    static String sanitise(String path) {
        var out = new ArrayList<String>();
        for (var part : (path == null ? "" : path).replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IllegalArgumentException("'..' is not allowed in paths");
            if (part.indexOf(':') >= 0) throw new IllegalArgumentException("':' is not allowed in paths");
            out.add(part);
        }
        return String.join("/", out);
    }
}

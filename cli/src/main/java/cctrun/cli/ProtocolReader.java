// SPDX-License-Identifier: MPL-2.0
package cctrun.cli;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounds UTF-8 framing before allocation; an oversized line is drained without retaining it. */
final class ProtocolReader {
    record Line(String text, boolean oversized) {}
    private final InputStream input;
    private final int limit;
    ProtocolReader(InputStream input, int limit) {
        this.input = new BufferedInputStream(input);
        this.limit = limit;
    }
    Line read() throws IOException {
        var bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        boolean oversized = false, any = false;
        int next;
        while ((next = input.read()) != -1) {
            any = true;
            if (next == '\n') break;
            if (!oversized) {
                if (bytes.size() == limit) oversized = true;
                else bytes.write(next);
            }
        }
        if (!any) return null;
        return new Line(oversized ? "" : bytes.toString(StandardCharsets.UTF_8), oversized);
    }
}

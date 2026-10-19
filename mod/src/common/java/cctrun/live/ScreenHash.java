// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
/** Stable content digest; dimension and row boundaries cannot collide by concatenation. */
final class ScreenHash {
    private ScreenHash() {}
    static String of(int width, int height, String[]... layers) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
            for (var layer : layers) {
                digest.update(ByteBuffer.allocate(4).putInt(layer.length).array());
                for (var row : layer) {
                    byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

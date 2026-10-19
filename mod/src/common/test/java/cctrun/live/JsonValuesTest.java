// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;
class JsonValuesTest {
    @Test void byteStringsAndNonFiniteNumbersKeepLuaSemantics() {
        assertEquals("\u00ff",JsonValues.of(new byte[]{(byte)255}).getAsString());
        assertEquals("??",JsonValues.of("\ud83d\ude00").getAsString());
        assertEquals("NaN",JsonValues.of(Double.NaN).getAsString());
        var bytes=ByteBuffer.wrap(new byte[]{1,2,3});bytes.position(1);
        JsonValues.of(bytes);assertEquals(1,bytes.position());
    }
}

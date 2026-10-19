// SPDX-License-Identifier: MPL-2.0
package cctrun.cli;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class ProtocolReaderTest {
    @Test void limitsBytesAndDrainsOversizedLinesBeforeContinuing() throws Exception {
        var reader = new ProtocolReader(new ByteArrayInputStream("ééé\n1234\nlast".getBytes(StandardCharsets.UTF_8)), 4);
        assertTrue(reader.read().oversized());
        assertEquals("1234", reader.read().text());
        assertEquals("last", reader.read().text());
        assertNull(reader.read());
    }
}

// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;

import dan200.computercraft.core.filesystem.MemoryMount;
import dan200.computercraft.api.filesystem.MountConstants;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RootMountTest {
    @Test void quotaCoversGrowthSeekAppendTruncationDeletionAndRenamedHandles() throws Exception {
        var mount = new RootMount(new MemoryMount(2500));
        try (var a = mount.openFile("a", MountConstants.WRITE_OPTIONS)) {
            a.write(ByteBuffer.wrap(new byte[1000]));
            assertEquals(1000, mount.getRemainingSpace());
            a.position(1900);
            assertThrows(IOException.class, () -> a.write(ByteBuffer.wrap(new byte[200])));
            assertEquals(1000, mount.getSize("a"));
            a.position(1000);
            mount.rename("a", "a.log");
            a.write(ByteBuffer.wrap(new byte[500]));
            assertEquals(1500, mount.getSize("a.log"));
        }
        try (var append = mount.openFile("a.log", MountConstants.APPEND_OPTIONS)) {
            assertThrows(IOException.class, () -> append.write(ByteBuffer.wrap(new byte[501])));
        }
        try (var truncate = mount.openFile("a.log", MountConstants.WRITE_OPTIONS)) {
            assertEquals(0, truncate.size());
            assertEquals(1500, mount.getRemainingSpace());
            mount.delete("a.log");
            assertFalse(truncate.isOpen());
            assertThrows(IOException.class, () -> truncate.write(ByteBuffer.wrap(new byte[1])));
        }
        assertEquals(2000, mount.getRemainingSpace());
        mount.makeDirectory("one/two/three");
        assertThrows(IOException.class, () -> mount.makeDirectory("four/five"));
        assertFalse(mount.exists("four"), "no partial directory creation on quota failure");
    }
}

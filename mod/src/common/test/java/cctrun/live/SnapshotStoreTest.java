// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTest {
    @TempDir Path dir;
    private void content(Path path,String value) throws IOException {
        Files.createDirectories(path); Files.writeString(path.resolve("data"),value);
        Files.writeString(path.resolve("manifest.json"),"{\"files\":{},\"dirs\":[],\"saved\":\"now\"}");
    }
    @Test void failedCopyAndSwitchKeepOldSnapshotAndRecoveryRestoresBackup() throws Exception {
        var target=dir.resolve("before");content(target,"old");
        assertThrows(IOException.class,()->SnapshotStore.replace(target,p->{throw new IOException("copy failure");}));
        assertEquals("old",Files.readString(target.resolve("data")));
        var moves=new AtomicInteger();
        assertThrows(IOException.class,()->SnapshotStore.replace(target,p->content(p,"new"),(a,b)->{
            if(moves.incrementAndGet()==2)throw new IOException("switch failure");
            SnapshotStore.move(a,b);
        }));
        assertEquals("old",Files.readString(target.resolve("data")));
        SnapshotStore.move(target,dir.resolve(".before.backup"));
        SnapshotStore.recoverAll(dir);
        assertEquals("old",Files.readString(target.resolve("data")));
        SnapshotStore.replace(target,p->content(p,"new"));
        assertEquals("new",Files.readString(target.resolve("data")));
    }
}

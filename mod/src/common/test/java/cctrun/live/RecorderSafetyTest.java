// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RecorderSafetyTest {
    @TempDir Path dir;
    @Test void rotationBoundsIndexAndReaderSnapshotSurvivesFurtherRotation() throws Exception {
        var trace = new ComputerTrace(() -> 1L, () -> 1.0, dir.resolve("computer.jsonl"), 4096);
        var data = new JsonObject(); data.addProperty("text", "x".repeat(100));
        for (int i=0;i<100;i++) trace.record("event", data);
        assertTrue(trace.rotations() > 2);
        assertTrue(trace.retainedRecords() < 40);
        assertEquals(trace.lastSeq()-trace.firstSeq()+1, trace.retainedRecords());
        assertEquals(trace.retainedRecords(), trace.countsAfter(0).values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(trace.firstAfter(0, Set.of("event")) >= trace.firstSeq());
        assertTrue(trace.countsAfter(Long.MAX_VALUE).isEmpty());
        assertEquals(-1,trace.firstAfter(Long.MAX_VALUE,Set.of("event")));
        var reader = trace.lines();
        for (int i=0;i<100;i++) trace.record("event", data);
        try (reader) {
            var records=reader.map(TraceRecords::parse).toList();
            for (int i=1;i<records.size();i++) assertEquals(records.get(i-1).get("seq").getAsLong()+1, records.get(i).get("seq").getAsLong());
        }
        assertTrue(Files.size(trace.file())+Files.size(trace.segments().get(0))<=4096);
        assertNull(trace.recordAt(1));
        assertEquals(trace.lastSeq(),trace.recordAt(trace.lastSeq()).get("seq").getAsLong());
        trace.close();
        try(var paths=Files.list(dir)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".trace-read-"))); }
    }
    @Test void partialWriteDoesNotCommitIndexAndRecordingRecoversAfterBackoff() throws Exception {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var now = new java.util.concurrent.atomic.AtomicLong();
        ComputerTrace.Opener opener = path -> {
            var delegate=Files.newByteChannel(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            return new java.nio.channels.SeekableByteChannel() {
                public int write(java.nio.ByteBuffer src) throws java.io.IOException {
                    if(fail.getAndSet(false)) {
                        int limit=src.limit();src.limit(src.position()+Math.min(4,src.remaining()));
                        delegate.write(src);src.limit(limit);throw new java.io.IOException("injected partial write");
                    }
                    return delegate.write(src);
                }
                public int read(java.nio.ByteBuffer b)throws java.io.IOException{return delegate.read(b);}
                public long position()throws java.io.IOException{return delegate.position();}
                public java.nio.channels.SeekableByteChannel position(long p)throws java.io.IOException{delegate.position(p);return this;}
                public long size()throws java.io.IOException{return delegate.size();}
                public java.nio.channels.SeekableByteChannel truncate(long p)throws java.io.IOException{delegate.truncate(p);return this;}
                public boolean isOpen(){return delegate.isOpen();}
                public void close()throws java.io.IOException{delegate.close();}
            };
        };
        var trace=new ComputerTrace(()->1L,()->1.0,dir.resolve("fault.jsonl"),4096,opener,now::get);
        long before=trace.lastSeq();fail.set(true);trace.record("event",new JsonObject());
        assertEquals(before,trace.lastSeq());
        assertEquals(1,trace.health().get("dropped_records").getAsInt());
        now.set(2_000_000_000L);trace.record("event",new JsonObject());
        assertTrue(trace.health().get("writable").getAsBoolean());
        try(var lines=trace.lines()){assertEquals(before+2,lines.map(TraceRecords::parse).count());}
        trace.close();
    }
    @Test void digestIncludesColorsSizeAndRowBoundaries() {
        var first=ScreenHash.of(2,1,new String[]{"ab"},new String[]{"00"},new String[]{"ff"});
        assertNotEquals(first,ScreenHash.of(2,1,new String[]{"ab"},new String[]{"11"},new String[]{"ff"}));
        assertNotEquals(first,ScreenHash.of(1,2,new String[]{"a","b"},new String[]{"0","0"},new String[]{"f","f"}));
        assertEquals(first,ScreenHash.of(2,1,new String[]{"ab"},new String[]{"00"},new String[]{"ff"}));
    }
    @Test void ringIsBoundedAndDrainResetsDroppedCount() {
        var ring=new Ring(2);
        for(int i=0;i<4;i++) ring.add(i,i,"e",new JsonObject());
        var dropped=new long[1]; var history=ring.drain(dropped);
        assertEquals(2,dropped[0]); assertEquals(2,history.get(0).tick());
        assertTrue(ring.drain(dropped).isEmpty()); assertEquals(0,dropped[0]);
    }
    @Test void missingInternalsReturnUnavailableInsteadOfFailingInitialization() {
        assertNull(CcInternals.optionalField(String.class,"noSuchField"));
        assertNull(CcInternals.optionalField("no.such.Class","terminal"));
    }
    @Test void repeatedAliasesForTheSamePeripheralAreAllowedButDifferentTargetsAreNot() {
        var map=new java.util.HashMap<String,String>();
        PeripheralNames.put(map,"chest",new String("physical-a"),String::equals);
        PeripheralNames.put(map,"chest",new String("physical-a"),String::equals);
        assertThrows(IllegalArgumentException.class,()->PeripheralNames.put(map,"chest","physical-b",String::equals));
        assertEquals("physical-a",map.get("chest"));
    }
    @Test void tokensHaveOwnerOnlyPermissionsAndSessionsNeverCollide() throws Exception {
        String token=LocalFiles.token(dir);
        assertEquals(token,LocalFiles.token(dir));
        if(Files.getFileStore(dir).supportsFileAttributeView("posix")) assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(dir.resolve("token.txt")));
        var first=LocalFiles.sessionPath(dir.resolve("sessions"));
        var second=LocalFiles.sessionPath(dir.resolve("sessions"));
        assertNotEquals(first,second);assertFalse(Files.exists(first.getParent()));
        LocalFiles.reserveSession(first);
        assertThrows(FileAlreadyExistsException.class,()->LocalFiles.reserveSession(first));
    }
    @Test void endpointCleanupPreservesUnknownAndOtherLiveOwners() throws Exception {
        var file=dir.resolve("endpoint.json");
        LocalFiles.writeEndpoint(file,"{\"pid\":"+ProcessHandle.current().pid()+",\"session\":\"other\"}");
        LocalFiles.removeDeadEndpoint(file);LocalFiles.removeOwnedEndpoint(file,"this");assertTrue(Files.exists(file));
        LocalFiles.removeOwnedEndpoint(file,"other");assertFalse(Files.exists(file));
        Files.writeString(file,"{");LocalFiles.removeDeadEndpoint(file);assertTrue(Files.exists(file));
        LocalFiles.writeEndpoint(file,"{\"pid\":9223372036854775807,\"session\":\"dead\"}");
        LocalFiles.removeDeadEndpoint(file);assertFalse(Files.exists(file));
    }
}

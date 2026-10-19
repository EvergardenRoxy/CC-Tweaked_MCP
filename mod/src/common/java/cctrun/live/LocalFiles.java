// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Local recorder files; no Minecraft class initialization is required. */
final class LocalFiles {
    private LocalFiles() {}
    static String token(Path home) throws IOException {
        Files.createDirectories(home);
        var file = home.resolve("token.txt");
        boolean posix = Files.getFileStore(home).supportsFileAttributeView("posix");
        if (Files.exists(file) && posix) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        if (!Files.exists(file)) {
            try {
                if (posix) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                else Files.createFile(file);
            } catch (FileAlreadyExistsException ignored) { }
        }
        var token = Files.readString(file).trim();
        if (!token.isEmpty()) return token;
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
        Files.writeString(file, token + "\n");
        return token;
    }
    static Path sessionPath(Path sessions) {
        var prefix = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(java.time.LocalDateTime.now());
        return sessions.resolve(prefix + "-" + java.util.UUID.randomUUID());
    }
    static void reserveSession(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.createDirectory(path);
    }
    static void removeDeadEndpoint(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try {
            var endpoint=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            long pid=endpoint.get("pid").getAsLong();
            if(pid>0 && ProcessHandle.of(pid).map(p->!p.isAlive()).orElse(true))Files.deleteIfExists(file);
        }catch(RuntimeException e){/* unknown ownership: preserve the file */}
    }
    static void removeOwnedEndpoint(Path file,String session) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try {
            var endpoint=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(session.equals(endpoint.get("session").getAsString()))Files.deleteIfExists(file);
        }catch(RuntimeException e){/* cannot establish ownership */}
    }
    static void writeEndpoint(Path file,String json) throws IOException {
        var stage=Files.createTempFile(file.getParent(),".endpoint-",".tmp");
        try {
            Files.writeString(stage,json+"\n");
            try{Files.move(stage,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(stage,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(stage);}
    }
}

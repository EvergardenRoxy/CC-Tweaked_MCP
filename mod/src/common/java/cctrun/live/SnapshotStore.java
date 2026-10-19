// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import java.nio.file.*;
import java.io.IOException;
import java.util.Comparator;

/** Recoverable same-filesystem directory replacement. Backups survive failures and process termination. */
final class SnapshotStore {
    @FunctionalInterface interface Writer { void write(Path stage) throws IOException; }
    @FunctionalInterface interface Mover { void move(Path source, Path target) throws IOException; }
    private SnapshotStore() {}
    static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
    }
    static synchronized void recover(Path target) throws IOException {
        var backup = target.resolveSibling("." + target.getFileName() + ".backup");
        if (Files.exists(backup) && !Files.exists(target)) move(backup, target);
    }
    static void recoverAll(Path root) throws IOException {
        if(!Files.isDirectory(root))return;
        try(var paths=Files.list(root)) {
            for(var backup:(Iterable<Path>)paths::iterator) {
                String name=backup.getFileName().toString();
                if(name.matches("\\.[A-Za-z0-9_-]{1,40}\\.backup"))recover(root.resolve(name.substring(1,name.length()-7)));
            }
        }
    }
    static void replace(Path target, Writer writer) throws IOException { replace(target, writer, SnapshotStore::move); }
    static synchronized void replace(Path target, Writer writer, Mover mover) throws IOException {
        Files.createDirectories(target.getParent());
        recover(target);
        var backup = target.resolveSibling("." + target.getFileName() + ".backup");
        if (Files.exists(backup)) {
            // A previous successful switch may have left the backup after cleanup failed. Keep it until the
            // current target's manifest is known to be valid, then it is safe to reuse the backup name.
            SnapshotManifest.parse(Files.readString(target.resolve("manifest.json")));
            deleteTree(backup);
        }
        var stage = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + ".stage-");
        boolean oldMoved = false;
        try {
            writer.write(stage);
            SnapshotManifest.parse(Files.readString(stage.resolve("manifest.json")));
            if (Files.exists(target)) { mover.move(target, backup); oldMoved = true; }
            try { mover.move(stage, target); }
            catch (IOException e) {
                if (oldMoved && !Files.exists(target)) {
                    try { mover.move(backup, target); }
                    catch (IOException rollback) { e.addSuppressed(rollback); }
                }
                throw e;
            }
            try { deleteTree(backup); }
            catch (IOException e) { System.getLogger("cct-live").log(System.Logger.Level.WARNING, "Snapshot saved; backup cleanup deferred: " + backup); }
        } finally {
            if (Files.exists(stage)) deleteTree(stage);
        }
    }
    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (var p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}

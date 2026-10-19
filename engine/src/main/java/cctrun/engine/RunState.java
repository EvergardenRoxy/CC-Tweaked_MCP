// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Last known process state. A stale running file is not proof that a process is still alive. */
public final class RunState {
    public static final String RESERVED_DIRECTORY = "cctrun.reservedRun";
    private static Path current;
    private RunState() {}

    public static Path reserve(Path parent) throws IOException {
        var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now());
        var dir = Runner.allocateRunDirectory(parent.toAbsolutePath().normalize(), timestamp, () -> java.util.UUID.randomUUID().toString().replace("-", ""));
        update(dir, "starting", null);
        return dir;
    }

    static Path directory(Path parent) throws IOException {
        var supplied = System.getProperty(RESERVED_DIRECTORY);
        if (supplied == null) return reserve(parent);
        var dir = Path.of(supplied).toAbsolutePath().normalize();
        if (!parent.toAbsolutePath().normalize().equals(dir.getParent()) || !Files.isRegularFile(dir.resolve("state.json"))) {
            throw new IOException("invalid reserved run directory");
        }
        var state = JsonParser.parseString(Files.readString(dir.resolve("state.json"))).getAsJsonObject();
        if (!"starting".equals(state.get("state").getAsString())) throw new IOException("run directory is already in use");
        return dir;
    }

    static synchronized void started(Path dir) throws IOException {
        current = dir;
        update(dir, "running", null);
    }

    public static synchronized void failedCurrent(String reason) {
        if (current == null) return;
        try { update(current, "failed", reason); }
        catch (IOException ignored) { System.err.println("cct-run: could not update run state"); }
    }

    public static synchronized void update(Path dir, String state, String reason) throws IOException {
        var target = dir.resolve("state.json");
        var out = Files.exists(target) ? JsonParser.parseString(Files.readString(target)).getAsJsonObject() : new JsonObject();
        out.addProperty("schema", "cct-run/state@1");
        out.addProperty("state", state);
        out.addProperty("updated_at", Instant.now().toString());
        out.addProperty("updated_by_pid", ProcessHandle.current().pid());
        if ("running".equals(state)) {
            out.addProperty("pid", ProcessHandle.current().pid());
            ProcessHandle.current().info().startInstant().ifPresent(start -> out.addProperty("pid_started_at", start.toString()));
        }
        if (reason != null) out.addProperty("reason", reason);
        var temp = Files.createTempFile(dir, ".state-", ".tmp");
        try {
            Files.writeString(temp, out + "\n");
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}

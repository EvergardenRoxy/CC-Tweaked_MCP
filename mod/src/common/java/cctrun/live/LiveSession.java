// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.core.metrics.MetricsObserver;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.core.ServerContext;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Minecraft server run (a world in single player). On demand (D40): nothing is observed until an AI attaches a
 * computer, except that recording mode keeps a short in-memory history per computer.
 * <p>
 * Each computer gets one trace file per session, {@code <game dir>/cct-live/sessions/<start time>/computer-<id>.jsonl};
 * repeated attachments append to it (seq keeps counting), separated by {@code attach}/{@code detach} records.
 */
final class LiveSession {
    private static final DateTimeFormatter DIR_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int FLUSH_EVERY_TICKS = 20;

    private static volatile LiveSession current;

    static LiveSession current() {
        return current;
    }

    static LiveSession start(MinecraftServer server, Path home) throws IOException {
        stop();
        var dir = home.resolve("sessions").resolve(LocalDateTime.now().format(DIR_NAME));
        JsonValues.MAX_STRING = LiveConfig.TRACE_MAX_STRING.get();
        var session = new LiveSession(server, home, dir);
        current = session;
        CctLive.LOG.info("cct-live: on demand; recording mode {}; attachments are written to {}", session.recording ? "on" : "off", dir);
        return session;
    }

    static void stop() {
        var session = current;
        current = null;
        if (session != null) session.close();
    }

    private final MinecraftServer server;
    private final Path home;
    private final Path dir;
    private final long startNanos = System.nanoTime();
    private final boolean recording = LiveConfig.RECORDER_ENABLED.get();
    private final int ringSize = LiveConfig.RECORDER_SIZE.get();
    private final long leaseMillis = LiveConfig.LEASE_SECONDS.get() * 1000L;
    private volatile long tick;
    /** Read on computer threads for every event and call: keep lookups cheap. */
    private final Map<MetricsObserver, Attachment> attached = new ConcurrentHashMap<>();
    private volatile boolean anyAttached;
    /** Recording mode; weak keys, so a removed computer's ring goes with it. */
    private final Map<MetricsObserver, Ring> rings = Collections.synchronizedMap(new WeakHashMap<>());
    /** Server thread: each computer's trace (kept open between attachments), and the latest trace per computer ID. */
    private final Map<MetricsObserver, ComputerTrace> traces = new HashMap<>();
    private final Map<Integer, ComputerTrace> traceById = new ConcurrentHashMap<>();
    private LiveHttp http;

    private LiveSession(MinecraftServer server, Path home, Path dir) {
        this.server = server;
        this.home = home;
        this.dir = dir;
    }

    MinecraftServer server() {
        return server;
    }

    /** cct-live's own directory (token, endpoint, sessions, snapshots). */
    Path home() {
        return home;
    }

    /** Where CC keeps this computer's files (saves/<world>/computercraft/computer/<id>). */
    Path computerDir(int id) {
        return ServerContext.get(server).storageDir().resolve("computer").resolve(Integer.toString(id)).toAbsolutePath().normalize();
    }

    Path dir() {
        return dir;
    }

    boolean recording() {
        return recording;
    }

    long leaseSeconds() {
        return leaseMillis / 1000;
    }

    long tickCount() {
        return tick;
    }

    double millis() {
        return Math.round((System.nanoTime() - startNanos) / 100_000.0) / 10.0;
    }

    /** Any thread. Null unless an AI has attached this computer. */
    Attachment attachedTo(MetricsObserver metrics) {
        return anyAttached ? attached.get(metrics) : null;
    }

    Ring ring(MetricsObserver metrics) {
        synchronized (rings) {
            return rings.computeIfAbsent(metrics, m -> new Ring(ringSize));
        }
    }

    // --- server thread ---------------------------------------------------------------------------------------------

    List<ServerComputer> computers() {
        return new ArrayList<>(ServerContext.get(server).registry().getComputers());
    }

    List<Attachment> attachments() {
        return new ArrayList<>(attached.values());
    }

    /** The first loaded computer with this ID, or null. */
    ServerComputer find(int id) {
        for (var computer : ServerContext.get(server).registry().getComputers()) if (computer.getID() == id) return computer;
        return null;
    }

    Attachment attachmentOf(ServerComputer computer) {
        return attached.get(computer.getMetrics());
    }

    /** The trace of the computer with this ID (also after it was unloaded), or null if it was never attached. */
    ComputerTrace traceOf(int id) {
        return traceById.get(id);
    }

    /** Attach, or renew an existing attachment; leaseSeconds null = the configured lease. */
    Attachment attach(ServerComputer computer, Long leaseSeconds) throws IOException {
        var existing = attached.get(computer.getMetrics());
        if (existing != null) {
            if (leaseSeconds != null) existing.lease(leaseSeconds);
            else existing.renew();
            return existing;
        }
        var id = computer.getID();
        var trace = traces.get(computer.getMetrics());
        if (trace == null) {
            Files.createDirectories(dir);
            var file = dir.resolve("computer-" + id + ".jsonl");
            // Two computers with the same ID (a copied computer) get separate files.
            for (var n = 2; Files.exists(file); n++) file = dir.resolve("computer-" + id + "-" + n + ".jsonl");
            trace = new ComputerTrace(this, file, LiveConfig.TRACE_MAX_MB.get() * 1024L * 1024L);
            traces.put(computer.getMetrics(), trace);
        }
        var attachment = new Attachment(computer, trace, leaseSeconds != null ? leaseSeconds * 1000L : leaseMillis);

        var start = new JsonObject();
        start.addProperty("lease_seconds", attachment.leaseSeconds());
        start.addProperty("recording_mode", recording);
        Ring.Entry[] history = new Ring.Entry[0];
        long[] dropped = {0};
        if (recording) {
            Ring ring;
            synchronized (rings) {
                ring = rings.get(computer.getMetrics());
            }
            if (ring != null) history = ring.drain(dropped).toArray(Ring.Entry[]::new);
            start.addProperty("history", history.length);
            start.addProperty("history_dropped", dropped[0]);
        }
        // Holding the trace's lock: records from the computer thread that arrive once the attachment is visible wait
        // until the history is written, so the file stays in order.
        synchronized (trace) {
            attached.put(computer.getMetrics(), attachment);
            anyAttached = true;
            trace.record("attach", start);
            // Recording-mode history first, marked as such, with the ticks it happened at.
            for (var entry : history) {
                var data = entry.data().deepCopy();
                data.addProperty("history", true);
                trace.recordAt(entry.tick(), entry.ms(), entry.kind(), data);
            }
            trace.record("computer_seen", Attachment.describe(computer));
        }
        traceById.put(id, trace);
        CctLive.announce(server, "cctlive.attached", "[cct-live] AI 已接管 %s 号电脑（租约 %s 秒，输入 /cctlive detach %s 可收回）", id, attachment.leaseSeconds(), id);
        return attachment;
    }

    /** End an attachment. reason: detach (by the AI), revoked (by a player), expired, removed, closed. */
    boolean detach(ServerComputer computer, String reason) {
        var attachment = attached.remove(computer.getMetrics());
        if (attachment == null) return false;
        anyAttached = !attached.isEmpty();
        attachment.end(reason);
        var id = computer.getID();
        switch (reason) {
            case "expired" -> CctLive.announce(server, "cctlive.expired", "[cct-live] AI 对 %s 号电脑的租约已到期，已自动释放", id);
            case "removed" -> CctLive.announce(server, "cctlive.removed", "[cct-live] %s 号电脑已被移除，接管结束", id);
            case "revoked" -> CctLive.announce(server, "cctlive.revoked", "[cct-live] 已收回 AI 对 %s 号电脑的控制", id);
            case "closed" -> {
            }
            default -> CctLive.announce(server, "cctlive.detached", "[cct-live] AI 已释放 %s 号电脑", id);
        }
        return true;
    }

    /** End of every server tick: only attached computers are touched. */
    void tick() {
        tick++;
        if (!anyAttached) return;
        var registry = ServerContext.get(server).registry();
        var now = System.currentTimeMillis();
        var screens = LiveConfig.TRACE_SCREENS.get();
        for (var attachment : attachments()) {
            var computer = attachment.computer();
            if (registry.get(computer.getInstanceUUID()) != computer) {
                detach(computer, "removed");
            } else if (attachment.expired(now)) {
                detach(computer, "expired");
            } else {
                try {
                    attachment.poll(screens);
                } catch (RuntimeException e) {
                    CctLive.LOG.warn("cct-live: polling computer {} failed", computer.getID(), e);
                }
                if (tick % FLUSH_EVERY_TICKS == 0) attachment.trace().flush();
            }
        }
    }

    // --- endpoint ----------------------------------------------------------------------------------------------------

    void startHttp(int port) throws IOException {
        http = new LiveHttp(this, port, token());
        http.start();
        // How `cct-run mcp --live <game dir>` finds this endpoint; removed again when the world closes.
        var endpoint = new JsonObject();
        endpoint.addProperty("schema", "cct-live/endpoint@0");
        endpoint.addProperty("url", "http://127.0.0.1:" + http.port() + "/");
        endpoint.addProperty("pid", ProcessHandle.current().pid());
        endpoint.addProperty("session", dir.toString());
        Files.writeString(endpointFile(), ComputerTrace.GSON.toJson(endpoint) + "\n", StandardCharsets.UTF_8);
    }

    private Path endpointFile() {
        return home.resolve("endpoint.json");
    }

    /** The access token for the HTTP endpoint: cct-live/token.txt, created once. */
    private String token() throws IOException {
        Files.createDirectories(home);
        var file = home.resolve("token.txt");
        if (Files.exists(file)) {
            var token = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!token.isEmpty()) return token;
        }
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        var token = HexFormat.of().formatHex(bytes);
        Files.writeString(file, token + "\n", StandardCharsets.UTF_8);
        return token;
    }

    private void close() {
        if (http != null) http.stop();
        // Remove endpoint.json even if the HTTP server was off or failed to bind: a leftover file would point
        // cct-run mcp at a URL nobody listens on.
        try {
            Files.deleteIfExists(endpointFile());
        } catch (IOException e) {
            CctLive.LOG.warn("cct-live: could not remove {}", endpointFile(), e);
        }
        for (var attachment : attachments()) detach(attachment.computer(), "closed");
        for (var trace : traces.values()) trace.close();
        rings.clear();
    }
}

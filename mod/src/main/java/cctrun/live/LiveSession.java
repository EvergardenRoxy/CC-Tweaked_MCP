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
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything observed while one Minecraft server runs (a world in single player). Traces go to
 * {@code <game dir>/cct-live/sessions/<start time>/computer-<id>.jsonl}.
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
        Files.createDirectories(dir);
        Files.writeString(home.resolve("sessions").resolve("latest.txt"), dir.getFileName().toString(), StandardCharsets.UTF_8);
        var session = new LiveSession(server, home, dir);
        current = session;
        CctLive.LOG.info("cct-live: recording CC: Tweaked computers to {}", dir);
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
    private volatile long tick;
    private final Map<MetricsObserver, Watched> byMetrics = new ConcurrentHashMap<>();
    /** Server thread only. */
    private final Map<ServerComputer, Watched> watched = new IdentityHashMap<>();
    private LiveHttp http;

    private LiveSession(MinecraftServer server, Path home, Path dir) {
        this.server = server;
        this.home = home;
        this.dir = dir;
    }

    MinecraftServer server() {
        return server;
    }

    Path dir() {
        return dir;
    }

    long tickCount() {
        return tick;
    }

    double millis() {
        return Math.round((System.nanoTime() - startNanos) / 100_000.0) / 10.0;
    }

    /** Called from computer threads. */
    Watched watchedFor(MetricsObserver metrics) {
        return byMetrics.get(metrics);
    }

    /** Server thread: the computers as of this tick. */
    List<Watched> computers() {
        return new ArrayList<>(watched.values());
    }

    /** Server thread, end of every server tick. */
    void tick() {
        tick++;
        var registry = ServerContext.get(server).registry();
        var seen = new IdentityHashMap<ServerComputer, Boolean>();
        var screens = LiveConfig.TRACE_SCREENS.get();
        for (var computer : registry.getComputers()) {
            seen.put(computer, Boolean.TRUE);
            var w = watched.get(computer);
            if (w == null) {
                w = open(computer);
                if (w == null) continue;
            }
            try {
                w.poll(screens);
            } catch (RuntimeException e) {
                CctLive.LOG.warn("cct-live: polling computer {} failed", computer.getID(), e);
            }
        }
        for (var it = watched.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (seen.containsKey(entry.getKey())) continue;
            byMetrics.remove(entry.getKey().getMetrics());
            entry.getValue().removed();
            it.remove();
        }
        if (tick % FLUSH_EVERY_TICKS == 0) for (var w : watched.values()) w.trace().flush();
    }

    private Watched open(ServerComputer computer) {
        var name = "computer-" + computer.getID();
        var file = dir.resolve(name + ".jsonl");
        // Two computers with the same ID (a copied disk or computer) get separate files.
        for (var n = 2; Files.exists(file); n++) file = dir.resolve(name + "-" + n + ".jsonl");
        try {
            var maxBytes = LiveConfig.TRACE_MAX_MB.get() * 1024L * 1024L;
            var w = new Watched(computer, new ComputerTrace(this, file, maxBytes));
            watched.put(computer, w);
            byMetrics.put(computer.getMetrics(), w);
            return w;
        } catch (IOException e) {
            CctLive.LOG.warn("cct-live: cannot write {}", file, e);
            return null;
        }
    }

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
        if (http != null) {
            http.stop();
            try {
                Files.deleteIfExists(endpointFile());
            } catch (IOException e) {
                CctLive.LOG.warn("cct-live: could not remove {}", endpointFile(), e);
            }
        }
        for (var w : watched.values()) w.trace().close();
        watched.clear();
        byMetrics.clear();
    }
}

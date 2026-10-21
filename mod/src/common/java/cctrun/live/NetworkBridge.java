// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** Local read-only data and session/control HTTP adapter. Never accepts a remote URL, path on disk, or arbitrary RPC operation. */
final class NetworkBridge implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger("cct-live");
    private final NetworkClient client;
    private final Path home;
    private final String owner = "network-client:" + UUID.randomUUID();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "cct-live-client-deadline"));
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), r -> daemon(r, "cct-live-client-http"), new ThreadPoolExecutor.AbortPolicy());
    private HttpServer server;
    private byte[] token;
    private int port;

    NetworkBridge(NetworkClient client, Path home) { this.client = client; this.home = home; }

    void start() throws IOException {
        token = LocalFiles.token(home).getBytes(StandardCharsets.UTF_8);
        LocalFiles.removeDeadEndpoint(home.resolve("endpoint.json"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 8);
        port = server.getAddress().getPort();
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
        var endpoint = new JsonObject();
        endpoint.addProperty("schema", "cct-live/endpoint@0");
        endpoint.addProperty("transport", "minecraft-network");
        endpoint.addProperty("url", "http://127.0.0.1:" + port + "/");
        endpoint.addProperty("pid", ProcessHandle.current().pid());
        endpoint.addProperty("session", owner);
        LocalFiles.writeEndpoint(home.resolve("endpoint.json"), endpoint.toString());
        LOG.log(System.Logger.Level.INFO, "cct-live: client bridge at 127.0.0.1:" + port + " (local discovery in " + home + ")");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!HttpPolicy.hostAllowed(exchange.getRequestHeaders().getFirst("Host"), port)) {
                HttpPolicy.discard(exchange, 2000, deadlines);
                send(exchange, NetworkProtocol.Reply.error(403, "Host must name the local bridge"));
                return;
            }
            if (!HttpPolicy.authorised(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
                HttpPolicy.discard(exchange, 2000, deadlines);
                send(exchange, NetworkProtocol.Reply.error(401, "missing or wrong client bridge token"));
                return;
            }
            HttpPolicy.read(exchange, 0, 2000, deadlines);
            var args = new JsonObject();
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            String operation;
            if (path.equals("/network") && method.equals("GET")) operation = "capabilities";
            else if (path.equals("/network/sessions/open") && method.equals("POST")) {
                operation = "session_open";
                args.addProperty("open_key", headerUuid(exchange, "X-CCT-Open-Key"));
            } else if (path.equals("/network/sessions/heartbeat") && method.equals("POST")) operation = "session_heartbeat";
            else if (path.equals("/network/sessions/close") && method.equals("POST")) operation = "session_close";
            else if (path.equals("/computers") && method.equals("GET")) operation = "computers";
            else {
                var parts = path.split("/");
                if (parts.length == 4 && parts[1].equals("computers") && method.equals("GET")
                    && Set.of("status", "screen", "control").contains(parts[3])) {
                    operation = parts[3].equals("control") ? "control_status" : parts[3];
                } else if (parts.length == 5 && parts[1].equals("computers") && parts[3].equals("control")
                    && method.equals("POST") && Set.of("acquire", "release").contains(parts[4])) operation = "control_" + parts[4];
                else {
                    send(exchange, NetworkProtocol.Reply.error(403, "operation unavailable in network preview"));
                    return;
                }
                int id = Integer.parseInt(parts[2]);
                if (id < 0) throw new IllegalArgumentException("computer id must be non-negative");
                args.addProperty("computer", id);
                if (Set.of("status", "screen", "control_release").contains(operation)) {
                    args.addProperty("lease_id", headerUuid(exchange, "X-CCT-Lease"));
                    args.addProperty("lease_epoch", Long.parseLong(exchange.getRequestHeaders().getFirst("X-CCT-Lease-Epoch")));
                }
            }
            if (!Set.of("capabilities", "session_open").contains(operation))
                args.addProperty("agent_session_id", headerUuid(exchange, "X-CCT-Session"));
            var query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                if (query.length() > 1024) throw new IllegalArgumentException("query too large");
                for (var part : query.split("&")) {
                    var pair = part.split("=", 2);
                    var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    var value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                    if (key.equals("source")) args.addProperty(key, value);
                    else if (key.equals("colors") || key.equals("regions")) args.addProperty(key, value.equals("1") || value.equals("true"));
                    // Existing status polling supplies these; network preview has no trace access.
                    else if (!Set.of("after", "kinds").contains(key)) throw new IllegalArgumentException("unsupported query: " + key);
                }
            }
            send(exchange, client.call(operation, args).get(11, TimeUnit.SECONDS));
        } catch (HttpPolicy.ReadTimeout ignored) {
            // The body deadline has closed the socket.
        } catch (HttpPolicy.TooLarge e) {
            send(exchange, NetworkProtocol.Reply.error(413, "network bridge requests must have an empty body"));
        } catch (IllegalArgumentException | NullPointerException e) {
            send(exchange, NetworkProtocol.Reply.error(400, e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            send(exchange, NetworkProtocol.Reply.error(504, "Minecraft request timed out"));
        } finally { exchange.close(); }
    }

    private static String headerUuid(HttpExchange exchange, String name) {
        var value = exchange.getRequestHeaders().getFirst(name);
        if (value == null) throw new IllegalArgumentException("missing " + name);
        return UUID.fromString(value).toString();
    }

    private static void send(HttpExchange exchange, NetworkProtocol.Reply reply) throws IOException {
        var bytes = reply.data().toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Thread daemon(Runnable work, String name) {
        var thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override public void close() {
        if (server != null) server.stop(0);
        workers.shutdownNow();
        deadlines.shutdownNow();
        try { LocalFiles.removeOwnedEndpoint(home.resolve("endpoint.json"), owner); }
        catch (IOException e) { LOG.log(System.Logger.Level.WARNING, "cct-live: could not remove client endpoint", e); }
    }
}

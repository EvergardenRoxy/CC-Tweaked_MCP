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

    /** Query parameters each read operation accepts; anything else is a 400. */
    private static final java.util.Map<String, Set<String>> QUERY = java.util.Map.of(
        "status", Set.of("source", "after", "kinds"),
        "screen", Set.of("source", "colors", "regions", "tick"),
        "files", Set.of("op", "path", "offset", "length"),
        "trace", Set.of("kind", "exclude", "after", "ticks", "contains", "peripheral", "method", "summary", "offset", "limit"),
        "body", Set.of("n", "offset", "length"));
    /** POST paths that carry a JSON object (the local endpoint's request bodies); a POST to files is a write. */
    private static final Set<String> JSON_POSTS = Set.of("files", "event", "power", "peripheral", "snapshot");
    private static final Set<String> BODY_OPERATIONS = Set.of("files_write", "event", "power", "peripheral", "snapshot");
    /** Largest accepted request body: a 1 MiB file write, JSON-escaped. */
    private static final int MAX_BODY = 3 * 1024 * 1024;
    private static final int CHUNK_CHARS = 8000;

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
            var bodyBytes = HttpPolicy.read(exchange, MAX_BODY, 5000, deadlines);
            var args = new JsonObject();
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            var segments = path.split("/");
            boolean takesBody = method.equals("POST") && segments.length == 4 && segments[1].equals("computers") && JSON_POSTS.contains(segments[3]);
            if (bodyBytes.length > 0 && !takesBody) {
                send(exchange, NetworkProtocol.Reply.error(413, "this network bridge request must have an empty body"));
                return;
            }
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
                    && Set.of("status", "screen", "control", "files", "trace", "body").contains(parts[3])) {
                    operation = parts[3].equals("control") ? "control_status" : parts[3];
                } else if (parts.length == 4 && parts[1].equals("computers") && method.equals("POST")
                    && JSON_POSTS.contains(parts[3])) {
                    operation = parts[3].equals("files") ? "files_write" : parts[3];
                } else if (parts.length == 5 && parts[1].equals("computers") && parts[3].equals("control")
                    && method.equals("POST") && Set.of("acquire", "release").contains(parts[4])) operation = "control_" + parts[4];
                else {
                    send(exchange, NetworkProtocol.Reply.error(403, "operation unavailable on the network bridge"));
                    return;
                }
                int id = Integer.parseInt(parts[2]);
                if (id < 0) throw new IllegalArgumentException("computer id must be non-negative");
                args.addProperty("computer", id);
                if (!Set.of("control_status", "control_acquire").contains(operation)) {
                    args.addProperty("lease_id", headerUuid(exchange, "X-CCT-Lease"));
                    args.addProperty("lease_epoch", Long.parseLong(exchange.getRequestHeaders().getFirst("X-CCT-Lease-Epoch")));
                }
            }
            if (!Set.of("capabilities", "session_open").contains(operation))
                args.addProperty("agent_session_id", headerUuid(exchange, "X-CCT-Session"));
            queryArguments(exchange, operation, args);
            if (!BODY_OPERATIONS.contains(operation)) {
                send(exchange, client.call(operation, args).get(11, TimeUnit.SECONDS));
                return;
            }
            var body = jsonBody(bodyBytes);
            if (operation.equals("files_write")) send(exchange, writeFile(args, body));
            else {
                args.add("body", body);
                send(exchange, client.call(operation, args).get(11, TimeUnit.SECONDS));
            }
        } catch (HttpPolicy.ReadTimeout ignored) {
            // The body deadline has closed the socket.
        } catch (HttpPolicy.TooLarge e) {
            send(exchange, NetworkProtocol.Reply.error(413, "request body larger than " + MAX_BODY + " bytes"));
        } catch (IllegalArgumentException | NullPointerException | com.google.gson.JsonParseException e) {
            send(exchange, NetworkProtocol.Reply.error(400, e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            send(exchange, NetworkProtocol.Reply.error(504, "Minecraft request timed out"));
        } finally { exchange.close(); }
    }

    /** Validates the query against the operation's whitelist and puts it into the RPC arguments. */
    private static void queryArguments(HttpExchange exchange, String operation, JsonObject args) {
        var raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return;
        if (raw.length() > 1024) throw new IllegalArgumentException("query too large");
        var allowed = QUERY.getOrDefault(operation, Set.of());
        var query = new JsonObject();
        for (var part : raw.split("&")) {
            var pair = part.split("=", 2);
            var key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            var value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (!allowed.contains(key)) throw new IllegalArgumentException("unsupported query: " + key);
            if (operation.equals("status") || operation.equals("screen")) {
                if (key.equals("colors") || key.equals("regions")) args.addProperty(key, value.equals("1") || value.equals("true"));
                else if (key.equals("after") || key.equals("tick")) args.addProperty(key, Long.parseLong(value));
                else args.addProperty(key, value);
            } else query.addProperty(key, value);
        }
        if (query.size() > 0) args.add("query", query);
    }

    private static JsonObject jsonBody(byte[] bytes) {
        var text = new String(bytes, StandardCharsets.UTF_8);
        var parsed = com.google.gson.JsonParser.parseString(text.isBlank() ? "{}" : text);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("body must be a JSON object");
        return parsed.getAsJsonObject();
    }

    /**
     * A game request is limited to {@link NetworkProtocol#MAX_REQUEST_CHARS}, so a bigger write goes as consecutive
     * appends. Every part is checked against the lease by the server; a failure reports how much was already written.
     */
    private NetworkProtocol.Reply writeFile(JsonObject args, JsonObject body) throws InterruptedException, ExecutionException, TimeoutException {
        if (!body.has("text") || !body.get("text").isJsonPrimitive() || !body.has("path")) {
            args.add("body", body); // delete, or an invalid body the server rejects
            return client.call("files_write", args).get(11, TimeUnit.SECONDS);
        }
        var text = body.get("text").getAsString();
        var path = body.get("path").getAsString();
        var budget = NetworkProtocol.MAX_REQUEST_CHARS - 1500 - 2 * path.length();
        if (budget < 1000) throw new IllegalArgumentException("path too long");
        boolean append = body.has("append") && body.get("append").getAsBoolean();
        long total = 0;
        int parts = 0;
        NetworkProtocol.Reply last = null;
        int from = 0;
        do {
            int to = Math.min(text.length(), from + CHUNK_CHARS);
            while (to > from + 1 && (new com.google.gson.JsonPrimitive(text.substring(from, to)).toString().length() > budget
                || (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1))))) to--;
            var part = body.deepCopy();
            part.addProperty("text", text.substring(from, to));
            part.addProperty("append", append || parts > 0);
            var call = args.deepCopy();
            call.add("body", part);
            last = callPaced(call);
            if (last.status() != 200) {
                if (parts > 0) {
                    last.data().addProperty("partial", true);
                    last.data().addProperty("written_so_far", total);
                }
                return last;
            }
            if (last.data().has("written")) total += last.data().get("written").getAsLong();
            parts++;
            from = to;
        } while (from < text.length());
        if (parts > 1) {
            last.data().addProperty("written", total);
            last.data().addProperty("parts", parts);
        }
        return last;
    }

    /** The server answers at most 20 requests per second per connection: wait out a 429 instead of failing the write. */
    private NetworkProtocol.Reply callPaced(JsonObject call) throws InterruptedException, ExecutionException, TimeoutException {
        for (int attempt = 0; ; attempt++) {
            var reply = client.call("files_write", call).get(11, TimeUnit.SECONDS);
            if (reply.status() != 429 || attempt >= 40) return reply;
            Thread.sleep(150);
        }
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

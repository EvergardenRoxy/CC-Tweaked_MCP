// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dan200.computercraft.shared.computer.core.ServerComputer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Local HTTP endpoint, bound to 127.0.0.1 only. Every request needs {@code Authorization: Bearer <token>} (token in
 * {@code cct-live/token.txt}); the Host header must name the loopback address (DNS rebinding guard).
 * <pre>
 * GET  /computers                                    computers on the server now
 * GET  /computers/{id}/screen                        text, colours, cursor
 * GET  /computers/{id}/trace?kind=a,b&offset=&limit= this session's trace records (offset counts matches)
 * POST /computers/{id}/event   {"name": "...", "args": [...]}
 * POST /computers/{id}/power   {"action": "on" | "shutdown" | "reboot"}
 * </pre>
 * Anything touching computers runs on the server thread.
 */
final class LiveHttp {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 5000;
    private static final Set<String> POWER = Set.of("on", "shutdown", "reboot");

    private final LiveSession session;
    private final int port;
    private final byte[] token;
    private HttpServer server;
    private ExecutorService executor;

    LiveHttp(LiveSession session, int port, String token) {
        this.session = session;
        this.port = port;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        executor = Executors.newFixedThreadPool(2, r -> {
            var t = new Thread(r, "cct-live-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        CctLive.LOG.info("cct-live: endpoint at http://127.0.0.1:{}/ (token in cct-live/token.txt)", port);
    }

    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Not try-with-resources: the catch blocks below still need to send a response.
        try {
            if (!hostAllowed(exchange)) {
                send(exchange, 403, error("Host header must be 127.0.0.1:" + port + " or localhost:" + port));
                return;
            }
            if (!authorised(exchange)) {
                send(exchange, 401, error("missing or wrong token (Authorization: Bearer <cct-live/token.txt>)"));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            var parts = path.replaceAll("^/+|/+$", "").split("/");
            var method = exchange.getRequestMethod();
            if (parts.length == 1 && parts[0].equals("computers") && method.equals("GET")) {
                send(exchange, 200, onServer(this::listComputers));
            } else if (parts.length == 3 && parts[0].equals("computers")) {
                int id;
                try {
                    id = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    send(exchange, 400, error("computer id must be a number"));
                    return;
                }
                var action = parts[2];
                if (action.equals("screen") && method.equals("GET")) {
                    send(exchange, 200, onServer(() -> screen(id)));
                } else if (action.equals("trace") && method.equals("GET")) {
                    send(exchange, 200, trace(id, query(exchange)));
                } else if (action.equals("event") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> queueEvent(id, body)));
                } else if (action.equals("power") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> power(id, body)));
                } else {
                    send(exchange, 404, error("unknown endpoint " + method + " " + path));
                }
            } else {
                send(exchange, 404, error("unknown endpoint " + method + " " + path));
            }
        } catch (BadRequest e) {
            send(exchange, e.status, error(e.getMessage()));
        } catch (Exception e) {
            CctLive.LOG.warn("cct-live: request failed", e);
            send(exchange, 500, error("internal: " + e));
        } finally {
            exchange.close();
        }
    }

    // Requests

    private JsonObject listComputers() {
        var list = new JsonArray();
        for (var w : session.computers()) {
            var data = Watched.describe(w.computer());
            data.addProperty("trace", session.dir().relativize(w.trace().file()).toString());
            list.add(data);
        }
        var out = new JsonObject();
        out.addProperty("session", session.dir().toString());
        out.addProperty("tick", session.tickCount());
        out.add("computers", list);
        return out;
    }

    private JsonObject screen(int id) {
        var w = find(id);
        var s = Terminals.snapshot(w.computer());
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("tick", session.tickCount());
        out.addProperty("on", w.computer().isOn());
        out.add("size", pair(s.width(), s.height()));
        out.add("cursor", pair(s.cursorX(), s.cursorY()));
        out.addProperty("cursor_blink", s.blink());
        out.add("lines", strings(s.text()));
        out.add("fg", strings(s.fg()));
        out.add("bg", strings(s.bg()));
        return out;
    }

    private JsonObject queueEvent(int id, JsonObject body) {
        var w = find(id);
        if (!body.has("name") || !body.get("name").isJsonPrimitive()) throw new BadRequest(400, "body needs \"name\" (string)");
        var name = body.get("name").getAsString();
        Object[] args = new Object[0];
        if (body.has("args")) {
            if (!body.get("args").isJsonArray()) throw new BadRequest(400, "\"args\" must be an array");
            var array = body.getAsJsonArray("args");
            args = new Object[array.size()];
            for (var i = 0; i < array.size(); i++) args[i] = toLua(array.get(i));
        }
        if (!w.computer().isOn()) throw new BadRequest(409, "computer " + id + " is off (POST /computers/" + id + "/power {\"action\":\"on\"})");
        w.computer().queueEvent(name, args);
        var out = new JsonObject();
        out.addProperty("queued", name);
        out.addProperty("tick", session.tickCount());
        return out;
    }

    private JsonObject power(int id, JsonObject body) {
        var w = find(id);
        var action = body.has("action") ? body.get("action").getAsString() : "";
        if (!POWER.contains(action)) throw new BadRequest(400, "\"action\" must be one of " + POWER);
        switch (action) {
            case "on" -> w.computer().turnOn();
            case "shutdown" -> w.computer().shutdown();
            default -> w.computer().reboot();
        }
        var out = new JsonObject();
        out.addProperty("action", action);
        out.addProperty("tick", session.tickCount());
        return out;
    }

    private JsonObject trace(int id, Map<String, String> query) throws IOException {
        var w = onServer(() -> find(id));
        var kinds = new HashSet<String>();
        if (query.containsKey("kind")) for (var k : query.get("kind").split(",")) if (!k.isBlank()) kinds.add(k.trim());
        var offset = intParam(query, "offset", 0, 0, Integer.MAX_VALUE);
        var limit = intParam(query, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        w.trace().flush();
        var records = new JsonArray();
        var matched = 0;
        try (var lines = Files.lines(w.trace().file(), StandardCharsets.UTF_8)) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                var line = it.next();
                if (line.isBlank()) continue;
                var record = JsonParser.parseString(line).getAsJsonObject();
                if (!kinds.isEmpty() && !kinds.contains(record.get("kind").getAsString())) continue;
                if (matched++ < offset) continue;
                if (records.size() < limit) records.add(record);
            }
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("file", w.trace().file().toString());
        out.addProperty("matched", matched);
        out.addProperty("offset", offset);
        out.add("records", records);
        if (offset + records.size() < matched) out.addProperty("next_offset", offset + records.size());
        return out;
    }

    // Helpers

    private Watched find(int id) {
        List<Watched> matches = new ArrayList<>();
        for (var w : session.computers()) if (w.computer().getID() == id) matches.add(w);
        if (matches.isEmpty()) throw new BadRequest(404, "no computer with id " + id + " is loaded");
        return matches.get(0);
    }

    private <T> T onServer(Supplier<T> task) {
        var server = session.server();
        try {
            return server.submit(task::get).get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new BadRequest(503, "server thread did not respond within 5 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequest(503, "interrupted");
        }
    }

    private static Object toLua(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive()) {
            var p = value.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        var table = new HashMap<Object, Object>();
        if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            for (var i = 0; i < array.size(); i++) table.put((double) (i + 1), toLua(array.get(i)));
        } else {
            for (var entry : value.getAsJsonObject().entrySet()) table.put(entry.getKey(), toLua(entry.getValue()));
        }
        return table;
    }

    private boolean hostAllowed(HttpExchange exchange) {
        var host = exchange.getRequestHeaders().getFirst("Host");
        return host == null || host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port);
    }

    private boolean authorised(HttpExchange exchange) {
        var header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        var text = new String(exchange.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
        try {
            var parsed = JsonParser.parseString(text.isBlank() ? "{}" : text);
            if (!parsed.isJsonObject()) throw new BadRequest(400, "body must be a JSON object");
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new BadRequest(400, "body is not valid JSON: " + e.getMessage());
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        var out = new HashMap<String, String>();
        var raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (var pair : raw.split("&")) {
            var eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static int intParam(Map<String, String> query, String name, int fallback, int min, int max) {
        if (!query.containsKey(name)) return fallback;
        try {
            var value = Integer.parseInt(query.get(name));
            if (value < min || value > max) throw new BadRequest(400, name + " must be in " + min + ".." + max);
            return value;
        } catch (NumberFormatException e) {
            throw new BadRequest(400, name + " must be a number");
        }
    }

    private static JsonArray pair(int a, int b) {
        var out = new JsonArray();
        out.add(a);
        out.add(b);
        return out;
    }

    private static JsonArray strings(String[] values) {
        var out = new JsonArray();
        for (var v : values) out.add(v);
        return out;
    }

    private static JsonObject error(String message) {
        var out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    private static void send(HttpExchange exchange, int status, JsonObject body) throws IOException {
        // ASCII-only like cct-run's stdout, so any console shows it correctly.
        var text = ComputerTrace.GSON.toJson(body);
        var ascii = new StringBuilder(text.length());
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c < 0x80) ascii.append(c);
            else ascii.append(String.format("\\u%04x", (int) c));
        }
        var bytes = ascii.toString().getBytes(StandardCharsets.US_ASCII);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class BadRequest extends RuntimeException {
        final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}

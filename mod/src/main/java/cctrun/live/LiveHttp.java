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
import java.util.HashMap;
import java.util.HashSet;
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
 * GET  /computers                                    computers on the server now (and which are attached)
 * GET  /computers/{id}/screen                        text, colours, cursor (no attachment needed)
 * POST /computers/{id}/attach                        start (or renew) an attachment: observe and control
 * POST /computers/{id}/detach                        end it
 * GET  /computers/{id}/trace?kind=a,b&after=&ticks=&offset=&limit=
 *                                                    this session's trace records; after = only seq > N,
 *                                                    ticks = N, A-B, A- or -B; offset counts matches
 * POST /computers/{id}/event   {"name": "...", "args": [...]}      needs an attachment
 * POST /computers/{id}/power   {"action": "on" | "shutdown" | "reboot"}   needs an attachment
 * </pre>
 * The trace is the current (or last) attachment's. Every request for an attached computer renews its lease.
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
        // Explicitly IPv4: InetAddress.getLoopbackAddress() is ::1 when the JVM prefers IPv6 addresses, and then
        // http://127.0.0.1:<port>/ is refused (seen with a Windows launcher).
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 16);
        executor = Executors.newFixedThreadPool(2, r -> {
            var t = new Thread(r, "cct-live-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        var bound = server.getAddress();
        CctLive.LOG.info("cct-live: endpoint at http://{}:{}/ (token in cct-live/token.txt)", bound.getAddress().getHostAddress(), bound.getPort());
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        // Not try-with-resources: the catch blocks below still need to send a response.
        try {
            if (!hostAllowed(exchange)) {
                send(exchange, 403, error("Host header must be 127.0.0.1:" + port + ", localhost:" + port + " or [::1]:" + port));
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
                } else if (action.equals("attach") && method.equals("POST")) {
                    send(exchange, 200, onServer(() -> attach(id)));
                } else if (action.equals("detach") && method.equals("POST")) {
                    send(exchange, 200, onServer(() -> detach(id)));
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
        for (var computer : session.computers()) {
            var data = Attachment.describe(computer);
            var attachment = session.attachmentOf(computer);
            data.addProperty("attached", attachment != null);
            if (attachment != null) data.addProperty("lease_seconds_left", attachment.secondsLeft());
            list.add(data);
        }
        var out = new JsonObject();
        out.addProperty("session", session.dir().toString());
        out.addProperty("tick", session.tickCount());
        out.addProperty("recording_mode", session.recording());
        out.addProperty("lease_seconds", session.leaseSeconds());
        out.add("computers", list);
        return out;
    }

    private JsonObject attach(int id) {
        var computer = find(id);
        Attachment attachment;
        try {
            attachment = session.attach(computer);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the trace: " + e.getMessage(), e);
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("attached", true);
        out.addProperty("tick", session.tickCount());
        out.addProperty("lease_seconds", session.leaseSeconds());
        out.addProperty("recording_mode", session.recording());
        out.addProperty("trace", attachment.trace().file().toString());
        return out;
    }

    private JsonObject detach(int id) {
        var computer = find(id);
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("detached", session.detach(computer, "detach"));
        out.addProperty("tick", session.tickCount());
        return out;
    }

    /** For input and power: the computer must be attached; renews the lease. */
    private ServerComputer controlled(int id) {
        var computer = find(id);
        var attachment = session.attachmentOf(computer);
        if (attachment == null) {
            throw new BadRequest(409, "computer " + id + " is not attached: POST /computers/" + id + "/attach first (the player sees a chat message)");
        }
        return computer;
    }

    private JsonObject screen(int id) {
        var computer = find(id);
        var s = Terminals.snapshot(computer);
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("tick", session.tickCount());
        out.addProperty("on", computer.isOn());
        out.addProperty("attached", session.attachmentOf(computer) != null);
        out.add("size", pair(s.width(), s.height()));
        out.add("cursor", pair(s.cursorX(), s.cursorY()));
        out.addProperty("cursor_blink", s.blink());
        out.add("lines", strings(s.text()));
        out.add("fg", strings(s.fg()));
        out.add("bg", strings(s.bg()));
        return out;
    }

    private JsonObject queueEvent(int id, JsonObject body) {
        var computer = controlled(id);
        if (!body.has("name") || !body.get("name").isJsonPrimitive()) throw new BadRequest(400, "body needs \"name\" (string)");
        var name = body.get("name").getAsString();
        Object[] args = new Object[0];
        if (body.has("args")) {
            if (!body.get("args").isJsonArray()) throw new BadRequest(400, "\"args\" must be an array");
            var array = body.getAsJsonArray("args");
            args = new Object[array.size()];
            for (var i = 0; i < array.size(); i++) args[i] = toLua(array.get(i));
        }
        if (!computer.isOn()) throw new BadRequest(409, "computer " + id + " is off (POST /computers/" + id + "/power {\"action\":\"on\"})");
        computer.queueEvent(name, args);
        var out = new JsonObject();
        out.addProperty("queued", name);
        out.addProperty("tick", session.tickCount());
        return out;
    }

    private JsonObject power(int id, JsonObject body) {
        var computer = controlled(id);
        var action = body.has("action") ? body.get("action").getAsString() : "";
        if (!POWER.contains(action)) throw new BadRequest(400, "\"action\" must be one of " + POWER);
        switch (action) {
            case "on" -> computer.turnOn();
            case "shutdown" -> computer.shutdown();
            default -> computer.reboot();
        }
        var out = new JsonObject();
        out.addProperty("action", action);
        out.addProperty("tick", session.tickCount());
        return out;
    }

    private JsonObject trace(int id, Map<String, String> query) throws IOException {
        // The last attachment's trace stays readable after the computer is unloaded.
        var attachment = onServer(() -> {
            var computer = session.find(id);
            if (computer == null) return null;
            var a = session.attachmentOf(computer);
            if (a != null) a.renew();
            return a;
        });
        var file = attachment != null ? attachment.trace().file() : session.traceFile(id);
        if (file == null) throw new BadRequest(409, "nothing recorded for computer " + id + " yet: POST /computers/" + id + "/attach starts recording");
        var kinds = new HashSet<String>();
        if (query.containsKey("kind")) for (var k : query.get("kind").split(",")) if (!k.isBlank()) kinds.add(k.trim());
        var after = intParam(query, "after", 0, 0, Integer.MAX_VALUE);
        long fromTick = 0, toTick = Long.MAX_VALUE;
        if (query.containsKey("ticks")) {
            var range = query.get("ticks").trim();
            try {
                var dash = range.indexOf('-');
                if (dash < 0) fromTick = toTick = Long.parseLong(range);
                else {
                    if (dash > 0) fromTick = Long.parseLong(range.substring(0, dash));
                    if (dash < range.length() - 1) toTick = Long.parseLong(range.substring(dash + 1));
                }
            } catch (NumberFormatException e) {
                throw new BadRequest(400, "ticks must be N, A-B, A- or -B");
            }
        }
        var offset = intParam(query, "offset", 0, 0, Integer.MAX_VALUE);
        var limit = intParam(query, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        if (attachment != null) attachment.trace().flush();
        var records = new JsonArray();
        var matched = 0;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                var line = it.next();
                if (line.isBlank()) continue;
                var record = JsonParser.parseString(line).getAsJsonObject();
                if (!kinds.isEmpty() && !kinds.contains(record.get("kind").getAsString())) continue;
                if (record.get("seq").getAsLong() <= after) continue;
                var tick = record.get("tick").getAsLong();
                if (tick < fromTick || tick > toTick) continue;
                if (matched++ < offset) continue;
                if (records.size() < limit) records.add(record);
            }
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("file", file.toString());
        out.addProperty("attached", attachment != null);
        out.addProperty("tick", session.tickCount());
        out.addProperty("matched", matched);
        out.addProperty("offset", offset);
        out.add("records", records);
        if (offset + records.size() < matched) out.addProperty("next_offset", offset + records.size());
        return out;
    }

    // Helpers

    /** Server thread. Any request for an attached computer renews its lease. */
    private ServerComputer find(int id) {
        var computer = session.find(id);
        if (computer == null) throw new BadRequest(404, "no computer with id " + id + " is loaded");
        var attachment = session.attachmentOf(computer);
        if (attachment != null) attachment.renew();
        return computer;
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
        return host == null || host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port) || host.equals("[::1]:" + port);
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

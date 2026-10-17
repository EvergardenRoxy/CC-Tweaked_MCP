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
 * GET  /computers/{id}/screen?source=&colors=1&regions=1&tick=N
 *                                                    text (no attachment needed); source = term (default) or the side of
 *                                                    an attached monitor; colors = fg/bg rows; regions = colour areas
 *                                                    (buttons ...); tick = rebuilt from the trace at that tick
 * GET  /computers/{id}/status?after=&kinds=&source=  small summary for polling: tick, last seq, new records by kind,
 *                                                    first record of kinds after seq, screen text and hash, monitors
 * GET  /computers/{id}/files?op=list|stat|read&path=&offset=&length=   the computer's files (needs an attachment)
 * POST /computers/{id}/files  {"path", "text", "encoding"?, "append"?}  write a file (needs an attachment and
 *                                                    config files.allow_write = true); {"op":"delete","path"} deletes,
 *                                                    only under /.cctlive (cct_live_lua's temporary files)
 * GET  /computers/{id}/body?n=&offset=&length=      a saved HTTP response body (handle record field "body")
 * POST /computers/{id}/attach                        start (or renew) an attachment: observe and control
 * POST /computers/{id}/detach                        end it
 * GET  /computers/{id}/trace?kind=a,b&after=&ticks=&contains=&offset=&limit=
 *                                                    this session's trace records; after = only seq > N,
 *                                                    ticks = N, A-B, A- or -B; contains = the record's url, path or
 *                                                    text has this substring (case-insensitive); offset counts matches
 * POST /computers/{id}/snapshot {"op": "save"|"diff"|"restore"|"list", "name"?}   needs an attachment; restore also
 *                                                    needs files.allow_write (it deletes files the snapshot lacks)
 * POST /computers/{id}/event   {"name": "...", "args": [...]}      needs an attachment
 * POST /computers/{id}/power   {"action": "on" | "shutdown" | "reboot"}   needs an attachment
 * </pre>
 * The trace is the current (or last) attachment's. Every request for an attached computer renews its lease.
 * Anything touching computers runs on the server thread.
 */
final class LiveHttp {
    private static final int DEFAULT_LIMIT = 50;
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
                    var query = query(exchange);
                    send(exchange, 200, query.containsKey("tick") ? pastScreen(id, query) : onServer(() -> screen(id, query)));
                } else if (action.equals("files") && method.equals("GET")) {
                    var query = query(exchange);
                    send(exchange, 200, onServer(() -> files(id, query)));
                } else if (action.equals("files") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> writeFile(id, body)));
                } else if (action.equals("body") && method.equals("GET")) {
                    send(exchange, 200, body(id, query(exchange)));
                } else if (action.equals("status") && method.equals("GET")) {
                    var query = query(exchange);
                    send(exchange, 200, status(id, query));
                } else if (action.equals("trace") && method.equals("GET")) {
                    send(exchange, 200, trace(id, query(exchange)));
                } else if (action.equals("event") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> queueEvent(id, body)));
                } else if (action.equals("power") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> power(id, body)));
                } else if (action.equals("attach") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> attach(id, body)));
                } else if (action.equals("snapshot") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> snapshot(id, body)));
                } else if (action.equals("peripheral") && method.equals("POST")) {
                    var body = body(exchange);
                    send(exchange, 200, onServer(() -> peripheral(id, body)));
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

    private JsonObject attach(int id, JsonObject body) {
        var computer = find(id);
        Long lease = null;
        if (body.has("lease_seconds") && !body.get("lease_seconds").isJsonNull()) {
            lease = body.get("lease_seconds").getAsLong();
            if (lease < 30 || lease > 86400) throw new BadRequest(400, "lease_seconds must be 30..86400");
        }
        Attachment attachment;
        try {
            attachment = session.attach(computer, lease);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the trace: " + e.getMessage(), e);
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("attached", true);
        out.addProperty("tick", session.tickCount());
        out.addProperty("lease_seconds", attachment.leaseSeconds());
        out.addProperty("recording_mode", session.recording());
        out.addProperty("last_seq", attachment.trace().lastSeq());
        out.add("monitors", strings(Terminals.monitors(computer).keySet().toArray(String[]::new)));
        out.addProperty("files_write", LiveConfig.FILES_WRITE.get());
        out.addProperty("computer_dir", session.computerDir(id).toString());
        return out;
    }

    /** Save, compare with or restore a snapshot of the computer's files (D46). */
    private JsonObject snapshot(int id, JsonObject body) {
        var computer = controlled(id);
        var op = body.has("op") ? body.get("op").getAsString() : "";
        var name = body.has("name") ? body.get("name").getAsString() : null;
        if (!ComputerSnapshots.ops().contains(op)) throw new BadRequest(400, "\"op\" must be one of " + ComputerSnapshots.ops());
        if (op.equals("restore") && !LiveConfig.FILES_WRITE.get()) {
            throw new BadRequest(403, "restoring writes and deletes files, and writing files is off: the player can turn it on with files.allow_write = true in config/cctlive-common.toml");
        }
        try {
            var out = switch (op) {
                case "save" -> ComputerSnapshots.save(computer, session.home(), name);
                case "diff" -> ComputerSnapshots.diff(computer, session.home(), name);
                case "restore" -> ComputerSnapshots.restore(computer, session.home(), name);
                default -> ComputerSnapshots.list(session.home(), id);
            };
            if (!op.equals("list") && !op.equals("diff")) {
                var record = new JsonObject();
                record.addProperty("op", op);
                record.addProperty("name", out.get("name").getAsString());
                if (op.equals("save")) record.addProperty("files", out.get("files").getAsInt());
                else {
                    record.add("written", out.get("written"));
                    record.add("deleted", out.get("deleted"));
                }
                record.addProperty("by", "ai");
                session.attachmentOf(computer).trace().record("snapshot", record);
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw new BadRequest(400, e.getMessage());
        } catch (IOException e) {
            throw new BadRequest(500, "snapshot " + op + " failed: " + e.getMessage());
        }
    }

    /** A getter of one of the computer's peripherals, called from Java while the computer does anything (PeripheralQuery). */
    private JsonObject peripheral(int id, JsonObject body) {
        var computer = controlled(id);
        if (!body.has("name")) throw new BadRequest(400, "\"name\" (a side or a wired network name) is required");
        var name = body.get("name").getAsString();
        var methodName = body.has("method") && !body.get("method").isJsonNull() ? body.get("method").getAsString() : null;
        var args = body.has("args") && body.get("args").isJsonArray() ? body.getAsJsonArray("args") : new JsonArray();
        JsonObject out;
        try {
            out = PeripheralQuery.call(computer, name, methodName, args);
        } catch (IllegalArgumentException e) {
            throw new BadRequest(400, e.getMessage());
        }
        if (methodName != null) {
            var record = out.deepCopy();
            record.remove("type");
            record.add("args", args);
            record.addProperty("by", "ai");
            session.attachmentOf(computer).trace().record("peripheral_query", record);
        }
        out.addProperty("tick", session.tickCount());
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

    private JsonObject screen(int id, Map<String, String> query) {
        var computer = find(id);
        var source = query.getOrDefault("source", "term");
        var terminal = Terminals.source(computer, source);
        if (terminal == null) {
            throw new BadRequest(404, "no monitor on side '" + source + "' of computer " + id + "; monitors: " + Terminals.monitors(computer).keySet());
        }
        var s = Terminals.snapshot(terminal);
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("source", source);
        out.addProperty("tick", session.tickCount());
        out.addProperty("on", computer.isOn());
        out.addProperty("attached", session.attachmentOf(computer) != null);
        describeScreen(out, s, flag(query, "regions"), flag(query, "colors"));
        return out;
    }

    /** Small enough to poll: what happened since seq {@code after}, and what the screen says now. */
    private JsonObject status(int id, Map<String, String> query) throws IOException {
        var source = query.getOrDefault("source", "term");
        var after = intParam(query, "after", 0, 0, Integer.MAX_VALUE);
        var wanted = new HashSet<String>();
        if (query.containsKey("kinds")) for (var k : query.get("kinds").split(",")) if (!k.isBlank()) wanted.add(k.trim());

        var out = onServer(() -> {
            var computer = find(id);
            var terminal = Terminals.source(computer, source);
            if (terminal == null) {
                throw new BadRequest(404, "no monitor on side '" + source + "' of computer " + id + "; monitors: " + Terminals.monitors(computer).keySet());
            }
            var s = Terminals.snapshot(terminal);
            var attachment = session.attachmentOf(computer);
            var o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("tick", session.tickCount());
            o.addProperty("on", computer.isOn());
            o.addProperty("attached", attachment != null);
            if (attachment != null) o.addProperty("lease_seconds_left", attachment.secondsLeft());
            o.add("monitors", strings(Terminals.monitors(computer).keySet().toArray(String[]::new)));
            o.addProperty("source", source);
            o.add("lines", Screens.compactLines(s.text()));
            o.addProperty("screen_hash", Integer.toHexString(java.util.Arrays.hashCode(s.text()) * 31 + java.util.Arrays.hashCode(s.bg())));
            return o;
        });
        var trace = session.traceOf(id);
        if (trace != null) {
            out.addProperty("last_seq", trace.lastSeq());
            // Records before first_seq were dropped to keep the trace within its size (trace.max_megabytes_per_computer).
            if (trace.rotations() > 0) out.addProperty("first_seq", trace.firstSeq());
            var counts = new JsonObject();
            for (var e : trace.countsAfter(after).entrySet()) counts.addProperty(e.getKey(), e.getValue());
            out.add("new_since_after", counts);
            var printed = trace.latest("printed_error");
            if (printed != null) out.add("last_printed_error", printed);
            if (!wanted.isEmpty()) {
                var seq = trace.firstAfter(after, wanted);
                if (seq > 0) out.add("match", recordAt(trace, seq));
            }
        }
        return out;
    }

    private static JsonObject recordAt(ComputerTrace trace, long seq) throws IOException {
        var prefix = "{\"seq\":" + seq + ",";
        try (var lines = trace.lines()) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                var line = it.next();
                if (line.startsWith(prefix)) return JsonParser.parseString(line).getAsJsonObject();
            }
        }
        return null;
    }

    /** The screen at a past tick, rebuilt from the computer's trace (only ticks while it was attached). */
    private JsonObject pastScreen(int id, Map<String, String> query) throws IOException {
        var tick = intParam(query, "tick", 0, 0, Integer.MAX_VALUE);
        var trace = onServer(() -> {
            var computer = session.find(id);
            if (computer != null) {
                var a = session.attachmentOf(computer);
                if (a != null) a.renew();
            }
            return session.traceOf(id);
        });
        if (trace == null) throw new BadRequest(409, "nothing recorded for computer " + id + " yet: POST /computers/" + id + "/attach starts recording");
        var source = query.getOrDefault("source", "term");
        var s = Screens.replay(trace, tick, source);
        if (s == null) {
            var dropped = trace.rotations() > 0 ? "; records before seq " + trace.firstSeq() + " were dropped to keep the trace within trace.max_megabytes_per_computer" : "";
            throw new BadRequest(404, "no screen recorded for computer " + id + " up to tick " + tick + dropped);
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("tick", tick);
        out.addProperty("source", source);
        out.addProperty("rebuilt_from_trace", true);
        describeScreen(out, s, flag(query, "regions"), flag(query, "colors"));
        return out;
    }

    /** Text rows always (trailing spaces and empty rows dropped); colours and regions only on request. */
    private static void describeScreen(JsonObject out, Terminals.Snapshot s, boolean regions, boolean colors) {
        out.add("size", pair(s.width(), s.height()));
        out.add("cursor", pair(s.cursorX(), s.cursorY()));
        out.addProperty("cursor_blink", s.blink());
        out.add("lines", Screens.compactLines(s.text()));
        if (colors) {
            out.add("fg", strings(s.fg()));
            out.add("bg", strings(s.bg()));
        }
        if (regions) out.add("regions", Screens.regions(s));
    }

    private JsonObject files(int id, Map<String, String> query) {
        var computer = controlled(id);
        var op = query.getOrDefault("op", "list");
        var path = query.getOrDefault("path", "/");
        try {
            return switch (op) {
                case "list" -> ComputerFiles.list(computer, path);
                case "stat" -> ComputerFiles.stat(computer, path);
                case "read" -> ComputerFiles.read(computer, path,
                    intParam(query, "offset", 0, 0, Integer.MAX_VALUE),
                    intParam(query, "length", ComputerFiles.DEFAULT_READ, 0, ComputerFiles.MAX_READ));
                default -> throw new BadRequest(400, "op must be list, stat or read");
            };
        } catch (IllegalArgumentException e) {
            throw new BadRequest(400, e.getMessage());
        } catch (IOException e) {
            throw new BadRequest(500, "cannot read the computer's files: " + e.getMessage());
        }
    }

    private JsonObject writeFile(int id, JsonObject body) {
        if (!LiveConfig.FILES_WRITE.get()) {
            throw new BadRequest(403, "writing files is off: the player can turn it on with files.allow_write = true in config/cctlive-common.toml");
        }
        var computer = controlled(id);
        if (body.has("op") && body.get("op").getAsString().equals("delete")) {
            if (!body.has("path")) throw new BadRequest(400, "delete needs \"path\"");
            try {
                var out = ComputerFiles.delete(computer, body.get("path").getAsString());
                var record = new JsonObject();
                record.addProperty("path", out.get("path").getAsString());
                record.addProperty("deleted", out.get("deleted").getAsBoolean());
                record.addProperty("by", "ai");
                session.attachmentOf(computer).trace().record("file_delete", record);
                return out;
            } catch (IllegalArgumentException e) {
                throw new BadRequest(400, e.getMessage());
            } catch (IOException e) {
                throw new BadRequest(500, "cannot delete: " + e.getMessage());
            }
        }
        if (!body.has("path") || !body.has("text")) throw new BadRequest(400, "body needs \"path\" and \"text\"");
        var encoding = body.has("encoding") ? body.get("encoding").getAsString() : "utf-8";
        if (!encoding.equals("utf-8") && !encoding.equals("iso-8859-1")) throw new BadRequest(400, "encoding must be utf-8 or iso-8859-1");
        var append = body.has("append") && body.get("append").getAsBoolean();
        try {
            var out = ComputerFiles.write(computer, body.get("path").getAsString(), body.get("text").getAsString(), encoding, append);
            var attachment = session.attachmentOf(computer);
            var record = new JsonObject();
            record.addProperty("path", out.get("path").getAsString());
            record.addProperty("bytes", out.get("written").getAsLong());
            record.addProperty("append", append);
            record.addProperty("by", "ai");
            attachment.trace().record("file_write", record);
            return out;
        } catch (IllegalArgumentException e) {
            throw new BadRequest(400, e.getMessage());
        } catch (IOException e) {
            throw new BadRequest(500, "cannot write the file: " + e.getMessage());
        }
    }

    /** A saved HTTP response body, read like a file. */
    private JsonObject body(int id, Map<String, String> query) throws IOException {
        var n = intParam(query, "n", 0, 1, Integer.MAX_VALUE);
        if (n == 0) throw new BadRequest(400, "n (the 'body' number of a handle record) is required");
        var offset = intParam(query, "offset", 0, 0, Integer.MAX_VALUE);
        var length = intParam(query, "length", ComputerFiles.DEFAULT_READ, 0, ComputerFiles.MAX_READ);
        var trace = onServer(() -> {
            var computer = session.find(id);
            if (computer != null) {
                var a = session.attachmentOf(computer);
                if (a != null) a.renew();
            }
            return session.traceOf(id);
        });
        if (trace == null) throw new BadRequest(409, "nothing recorded for computer " + id + " yet");
        var file = trace.bodyFile(n);
        if (!Files.exists(file)) throw new BadRequest(404, "no saved body " + n + " for computer " + id);
        var size = Files.size(file);
        byte[] bytes;
        try (var channel = Files.newByteChannel(file)) {
            channel.position(Math.min(offset, size));
            var buffer = java.nio.ByteBuffer.allocate((int) Math.max(0, Math.min(length, size - Math.min(offset, size))));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading
            }
            bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("body", n);
        out.addProperty("size", size);
        out.addProperty("offset", offset);
        out.addProperty("length", bytes.length);
        out.addProperty("eof", offset + bytes.length >= size);
        ComputerFiles.putText(out, bytes);
        return out;
    }

    private static boolean flag(Map<String, String> query, String name) {
        var value = query.get(name);
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
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
        var computerTrace = attachment != null ? attachment.trace() : session.traceOf(id);
        var file = computerTrace == null ? null : computerTrace.file();
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
        var excluded = new HashSet<String>();
        if (query.containsKey("exclude")) for (var k : query.get("exclude").split(",")) if (!k.isBlank()) excluded.add(k.trim());
        var summary = flag(query, "summary");
        var contains = query.containsKey("contains") ? query.get("contains").toLowerCase(java.util.Locale.ROOT) : null;
        var peripheralName = query.get("peripheral");
        var methodName = query.get("method");
        var byKind = new java.util.TreeMap<String, long[]>();

        var records = new JsonArray();
        var matched = 0;
        try (var lines = computerTrace.lines()) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                var line = it.next();
                if (line.isBlank()) continue;
                var record = JsonParser.parseString(line).getAsJsonObject();
                var kind = record.get("kind").getAsString();
                if (!kinds.isEmpty() && !kinds.contains(kind)) continue;
                if (excluded.contains(kind)) continue;
                if (record.get("seq").getAsLong() <= after) continue;
                var tick = record.get("tick").getAsLong();
                if (tick < fromTick || tick > toTick) continue;
                if (contains != null && !mentions(record, contains)) continue;
                if (peripheralName != null && !peripheralName.equals(stringOf(record, "peripheral"))) continue;
                if (methodName != null && !methodName.equals(stringOf(record, "method"))) continue;
                if (summary) {
                    // count, first seq, last seq, first tick, last tick
                    var seq = record.get("seq").getAsLong();
                    var entry = byKind.computeIfAbsent(kind, k -> new long[]{0, seq, seq, tick, tick});
                    entry[0]++;
                    entry[2] = seq;
                    entry[4] = tick;
                    matched++;
                    continue;
                }
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
        if (computerTrace.rotations() > 0) out.addProperty("first_seq", computerTrace.firstSeq());
        if (summary) {
            var kindsOut = new JsonObject();
            for (var e : byKind.entrySet()) {
                var v = e.getValue();
                var k = new JsonObject();
                k.addProperty("count", v[0]);
                k.add("seq", pair((int) v[1], (int) v[2]));
                k.add("ticks", pair((int) v[3], (int) v[4]));
                kindsOut.add(e.getKey(), k);
            }
            out.add("kinds", kindsOut);
            out.addProperty("last_seq", computerTrace.lastSeq());
            return out;
        }
        out.addProperty("offset", offset);
        out.add("records", records);
        if (offset + records.size() < matched) out.addProperty("next_offset", offset + records.size());
        return out;
    }

    // Helpers

    /** True if the record's url, path, program, text, event name, peripheral, method or arguments (lower case) contain the needle. */
    private static boolean mentions(JsonObject record, String needle) {
        for (var key : new String[]{"url", "path", "program", "text", "name", "peripheral", "method"}) {
            var v = record.get(key);
            if (v != null && v.isJsonPrimitive() && v.getAsString().toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        }
        return mentions(record.get("args"), needle);
    }

    private static String stringOf(JsonObject record, String key) {
        var v = record.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    /** Strings anywhere in call arguments (http.request takes a URL or a table with url = ...). */
    private static boolean mentions(JsonElement value, String needle) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonPrimitive()) return value.getAsString().toLowerCase(java.util.Locale.ROOT).contains(needle);
        if (value.isJsonArray()) {
            for (var v : value.getAsJsonArray()) if (mentions(v, needle)) return true;
            return false;
        }
        for (var e : value.getAsJsonObject().entrySet()) if (mentions(e.getValue(), needle)) return true;
        return false;
    }

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

// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.core.ServerContext;
import dan200.computercraft.shared.computer.menu.ComputerMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.*;
import java.util.function.Consumer;

/** Server-thread-only RPC service. The transport supplies the authenticated player; packets cannot choose one. */
final class NetworkServer {
    private final LiveSession session;
    private final Map<UUID, Peer> peers = new HashMap<>();
    /** The local endpoint's request handlers, reused without an HTTP server: authorization and leases are checked here first. */
    private final LiveHttp ops;
    /** Computers this service attached itself (and so detaches when the lease goes), not ones an administrator attached. */
    private final Set<ServerComputer> networkAttached = new HashSet<>();
    private static final Set<String> CONTROL_OPERATIONS = Set.of("files", "files_write", "event", "power", "peripheral", "snapshot", "trace", "body");
    private final NetworkControl control = new NetworkControl(LiveConfig.NETWORK_LEASE_SECONDS.get(), LiveConfig.NETWORK_IDLE_SECONDS.get());

    private NetworkControl.Owner owner(ServerPlayer player, Peer peer) { return new NetworkControl.Owner(player.getUUID(), peer.epoch); }

    private static final class Peer {
        final Object connection;
        UUID epoch;
        final NetworkGrants<ServerComputer> grants = new NetworkGrants<>();
        final NetworkRateLimit rate = new NetworkRateLimit();
        Peer(Object connection) { this.connection = connection; }
        /** Clicks and commands send no RPC reply, so anything over the limit is simply ignored. */
        boolean permit() { return rate.next() == NetworkRateLimit.Decision.ADMIT; }
    }

    NetworkServer(LiveSession session) {
        this.session = session;
        this.ops = new LiveHttp(session, 0, "");
        CctLive.LOG.info("cct-live network authorization: {}; MCP lease {}s / idle {}s",
            NetworkPlatform.ftbLoaded() ? "FTB Chunks effective interact AND edit" : "no claims integration; all players may authorize",
            LiveConfig.NETWORK_LEASE_SECONDS.get(), LiveConfig.NETWORK_IDLE_SECONDS.get());
    }

    void handle(ServerPlayer player, String json, Consumer<NetworkProtocol.Frame> reply) {
        if (json.length() > NetworkProtocol.MAX_REQUEST_CHARS || !player.connection.isAcceptingMessages()) return;
        var peer = peer(player);
        // Bound both server work and response amplification per connection: at most one 429 per window.
        var decision = peer.rate.next();
        if (decision == NetworkRateLimit.Decision.DROP) return;
        UUID id, epoch;
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
            id = UUID.fromString(request.get("request_id").getAsString());
            epoch = UUID.fromString(request.get("epoch").getAsString());
        } catch (RuntimeException e) { return; }
        NetworkProtocol.Reply result;
        try {
            if (decision == NetworkRateLimit.Decision.LIMITED)
                result = NetworkProtocol.Reply.error(429, "too many game requests on this connection; slow down");
            else if (!LiveConfig.NETWORK_ENABLED.get()) result = NetworkProtocol.Reply.error(403, "network bridge disabled by server");
            else {
                var operation = request.get("operation").getAsString();
                var arguments = request.getAsJsonObject("arguments");
                if (operation.equals("hello")) result = hello(player, peer, epoch, arguments);
                else if (!epoch.equals(peer.epoch)) result = NetworkProtocol.Reply.error(409, "handshake required for this connection");
                else result = execute(player, peer, operation, arguments);
            }
        } catch (NetworkControl.Failure e) {
            result = NetworkProtocol.Reply.error(e.status, e.getMessage());
        } catch (CcInternals.MissingCapability e) {
            result = NetworkProtocol.Reply.error(503, e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException | UnsupportedOperationException e) {
            result = NetworkProtocol.Reply.error(400, "invalid network request");
        } catch (RuntimeException e) {
            CctLive.LOG.warn("cct-live: network read failed", e);
            result = NetworkProtocol.Reply.error(500, "network read failed; see server log");
        }
        for (var frame : NetworkProtocol.frames(id, epoch, result)) reply.accept(frame);
    }

    private NetworkProtocol.Reply hello(ServerPlayer player, Peer peer, UUID epoch, JsonObject arguments) {
        if (!Integer.toString(NetworkProtocol.VERSION).equals(arguments.get("version").getAsString()))
            return NetworkProtocol.Reply.error(426, "incompatible cct-live protocol; server requires version " + NetworkProtocol.VERSION);
        if (!epoch.equals(peer.epoch)) {
            if (peer.epoch != null) control.remove(owner(player, peer));
            peer.grants.clear();
        }
        peer.epoch = epoch;
        return new NetworkProtocol.Reply(200, capabilities());
    }

    private static JsonObject capabilities() {
        var out = new JsonObject();
        out.addProperty("protocol", NetworkProtocol.VERSION);
        out.addProperty("transport", "minecraft-network");
        out.addProperty("control", true);
        out.addProperty("read_only", false);
        out.addProperty("files_write", LiveConfig.FILES_WRITE.get());
        out.addProperty("exclusive_control", true);
        var operations = new JsonArray();
        for (var name : NetworkProtocol.CAPABILITIES) operations.add(name);
        out.add("capabilities", operations);
        return out;
    }

    private NetworkProtocol.Reply execute(ServerPlayer player, Peer peer, String operation, JsonObject arguments) {
        if (operation.equals("capabilities")) return new NetworkProtocol.Reply(200, capabilities());
        if (operation.equals("menu_grant")) return menuGrant(player, peer, arguments);
        var owner = owner(player, peer);
        if (operation.equals("session_open")) {
            var agent = control.open(owner, uuid(arguments, "open_key"));
            var out = new JsonObject();
            out.addProperty("agent_session_id", agent.id().toString());
            out.addProperty("lease_seconds", LiveConfig.NETWORK_LEASE_SECONDS.get());
            return new NetworkProtocol.Reply(200, out);
        }
        var agent = uuid(arguments, "agent_session_id");
        control.require(owner, agent);
        if (operation.equals("session_close")) { control.close(owner, agent); return ok(); }
        if (operation.equals("session_heartbeat")) { control.heartbeat(owner, agent); return ok(); }
        if (operation.equals("computers")) {
            refresh(player, peer);
            var out = capabilities();
            var computers = new JsonArray();
            for (var grant : peer.grants.live(this::available)) computers.add(describe(peer, grant));
            out.add("computers", computers);
            out.addProperty("tick", session.tickCount());
            out.addProperty("recording_mode", false);
            return new NetworkProtocol.Reply(200, out);
        }
        if (!CONTROL_OPERATIONS.contains(operation)
            && !Set.of("status", "screen", "control_acquire", "control_status", "control_release").contains(operation))
            return NetworkProtocol.Reply.error(403, "operation unavailable in network preview");
        if (operation.equals("control_acquire")) prune();
        var idValue = arguments.get("computer");
        int id = idValue.getAsInt();
        if (id < 0 || !Integer.toString(id).equals(idValue.getAsString())) throw new IllegalArgumentException("invalid computer ID");
        NetworkGrants.Grant<ServerComputer> grant;
        try { grant = peer.grants.require(id, this::available); }
        catch (IllegalArgumentException e) { return NetworkProtocol.Reply.error(403, e.getMessage()); }
        var computer = grant.target();
        try { NetworkAuthorization.check(player, computer); }
        catch (NetworkControl.Failure e) { revoke(player, peer, id); throw e; }
        if (operation.equals("control_acquire")) {
            var lease = control.acquire(owner, agent, id, grant.instance());
            var out = leaseData(lease, agent);
            try { attach(player, computer, out); }
            catch (NetworkControl.Failure e) {
                if (lease.agent().equals(agent)) control.release(owner, agent, id, grant.instance(), lease.id(), lease.epoch());
                throw e;
            }
            return new NetworkProtocol.Reply(200, out);
        }
        if (operation.equals("control_status")) return new NetworkProtocol.Reply(200, leaseData(control.state(owner, agent, id), agent));
        var leaseId = uuid(arguments, "lease_id");
        var epochValue = arguments.get("lease_epoch");
        var leaseEpoch = epochValue.getAsLong();
        if (leaseEpoch < 1 || !Long.toString(leaseEpoch).equals(epochValue.getAsString())) throw new IllegalArgumentException("invalid lease epoch");
        if (operation.equals("control_release")) {
            control.release(owner, agent, id, grant.instance(), leaseId, leaseEpoch);
            if (networkAttached.remove(computer)) session.detach(computer, "detach");
            return ok();
        }
        control.validate(owner, agent, id, grant.instance(), leaseId, leaseEpoch, true);
        // Polling status keeps the lock busy; it must keep the recording attachment alive too.
        var attached = session.attachmentOf(computer);
        if (attached != null) attached.renew();
        if (CONTROL_OPERATIONS.contains(operation)) return controlOperation(player, computer, operation, arguments);
        var source = arguments.has("source") ? arguments.get("source").getAsString() : "term";
        if (!source.equals("term")) {
            var blocks = Terminals.monitorBlocks(computer, source);
            if (blocks.isEmpty()) return NetworkProtocol.Reply.error(404, "no authorized screen on side " + source);
            // A monitor denial is about that monitor only; it does not revoke the computer's grant.
            NetworkAuthorization.checkBlocks(player, computer.getLevel(), blocks);
        }
        if (operation.equals("screen") && arguments.has("tick")) {
            var past = new HashMap<String, String>();
            past.put("tick", arguments.get("tick").getAsString());
            past.put("source", source);
            if (arguments.has("colors") && arguments.get("colors").getAsBoolean()) past.put("colors", "1");
            if (arguments.has("regions") && arguments.get("regions").getAsBoolean()) past.put("regions", "1");
            try { return new NetworkProtocol.Reply(200, ops.pastScreen(id, past)); }
            catch (LiveHttp.BadRequest e) { return NetworkProtocol.Reply.error(e.status, e.getMessage()); }
            catch (java.io.IOException e) { return failed(e); }
        }
        var terminal = Terminals.source(computer, source);
        if (terminal == null) return NetworkProtocol.Reply.error(404, "no authorized screen on side " + source);
        var snapshot = Terminals.snapshot(terminal);
        var out = describe(peer, grant);
        out.addProperty("tick", session.tickCount());
        out.addProperty("source", source);
        out.add("lines", Screens.compactLines(snapshot.text()));
        out.addProperty("screen_hash", ScreenHash.of(snapshot.width(), snapshot.height(), snapshot.text(), snapshot.fg(), snapshot.bg()));
        var attachment = session.attachmentOf(computer);
        out.addProperty("trace_available", attachment != null);
        if (operation.equals("status")) {
            if (attachment != null) {
                var wanted = new HashSet<String>();
                if (arguments.has("kinds")) for (var kind : arguments.get("kinds").getAsString().split(",")) if (!kind.isBlank()) wanted.add(kind.trim());
                try { LiveHttp.addTrace(out, attachment.trace(), arguments.has("after") ? arguments.get("after").getAsLong() : 0, wanted); }
                catch (java.io.IOException e) { return failed(e); }
            }
            out.add("monitors", strings(Terminals.monitors(computer).keySet().stream()
                .filter(side -> monitorReadable(player, computer, side)).toArray(String[]::new)));
        } else {
            out.add("size", pair(snapshot.width(), snapshot.height()));
            out.add("cursor", pair(snapshot.cursorX(), snapshot.cursorY()));
            out.addProperty("cursor_blink", snapshot.blink());
            if (arguments.has("colors") && arguments.get("colors").getAsBoolean()) {
                out.add("fg", strings(snapshot.fg()));
                out.add("bg", strings(snapshot.bg()));
            }
            if (arguments.has("regions") && arguments.get("regions").getAsBoolean()) out.add("regions", Screens.regions(snapshot));
        }
        return new NetworkProtocol.Reply(200, out);
    }

    /** Attach (record) the computer for the lease holder; the same trace the local endpoint offers. */
    private void attach(ServerPlayer player, ServerComputer computer, JsonObject out) {
        boolean fresh = session.attachmentOf(computer) == null;
        try { session.attach(computer, Math.max(30L, Math.min(86400L, (long) LiveConfig.NETWORK_IDLE_SECONDS.get()))); }
        catch (java.io.IOException e) {
            CctLive.LOG.warn("cct-live: cannot start recording computer {}", computer.getID(), e);
            throw new NetworkControl.Failure(500, "cannot start recording; see server log");
        }
        if (fresh) networkAttached.add(computer);
        out.addProperty("attached", true);
        out.addProperty("files_write", LiveConfig.FILES_WRITE.get());
        out.addProperty("recording_mode", session.recording());
        out.add("monitors", strings(Terminals.monitors(computer).keySet().stream()
            .filter(side -> monitorReadable(player, computer, side)).toArray(String[]::new)));
    }

    /** Files, events, power, peripherals, snapshots, trace and bodies: the lease holder gets what the local endpoint offers. */
    private NetworkProtocol.Reply controlOperation(ServerPlayer player, ServerComputer computer, String operation, JsonObject arguments) {
        int id = computer.getID();
        if (session.find(id) != computer)
            return NetworkProtocol.Reply.error(409, "another loaded computer has this ID; operations are refused until only one remains");
        if (session.attachmentOf(computer) == null)
            return NetworkProtocol.Reply.error(409, "AI control of computer " + id + " has ended (a player used /cctlive detach, or it expired): "
                + "cct_live_control release, then acquire again");
        var query = new HashMap<String, String>();
        if (arguments.has("query")) for (var entry : arguments.getAsJsonObject("query").entrySet()) query.put(entry.getKey(), entry.getValue().getAsString());
        var body = arguments.has("body") ? arguments.getAsJsonObject("body") : new JsonObject();
        try {
            JsonObject out;
            switch (operation) {
                case "files" -> out = ops.files(id, query);
                case "files_write" -> out = ops.writeFile(id, body);
                case "event" -> { checkTouch(player, computer, body); out = ops.queueEvent(id, body); }
                case "power" -> out = ops.power(id, body);
                case "peripheral" -> out = ops.peripheral(id, body);
                case "snapshot" -> out = ops.snapshot(id, body);
                case "trace" -> out = ops.trace(id, query);
                default -> out = ops.body(id, query);
            }
            return new NetworkProtocol.Reply(200, out);
        } catch (LiveHttp.BadRequest e) {
            return NetworkProtocol.Reply.error(e.status, e.getMessage());
        } catch (java.io.IOException e) {
            return failed(e);
        }
    }

    /** Touching a monitor needs the same claim permission as reading it. */
    private static void checkTouch(ServerPlayer player, ServerComputer computer, JsonObject event) {
        if (!event.has("name") || !event.get("name").isJsonPrimitive() || !event.get("name").getAsString().equals("monitor_touch")) return;
        if (!event.has("args") || !event.get("args").isJsonArray() || event.getAsJsonArray("args").isEmpty()) return;
        var side = event.getAsJsonArray("args").get(0);
        if (!side.isJsonPrimitive()) return;
        var blocks = Terminals.monitorBlocks(computer, side.getAsString());
        if (!blocks.isEmpty()) NetworkAuthorization.checkBlocks(player, computer.getLevel(), blocks);
    }

    private static NetworkProtocol.Reply failed(java.io.IOException e) {
        CctLive.LOG.warn("cct-live: network request failed", e);
        return NetworkProtocol.Reply.error(500, "network request failed; see server log");
    }

    /** Detach what this service attached once nobody holds its lease (released, expired, revoked, disconnected). */
    private void releaseUnheld() {
        networkAttached.removeIf(computer -> {
            if (session.attachmentOf(computer) == null) return true; // a player ended it
            if (control.held(computer.getID())) return false;
            session.detach(computer, "detach");
            return true;
        });
    }

    private static boolean monitorReadable(ServerPlayer player, ServerComputer computer, String side) {
        try { NetworkAuthorization.checkBlocks(player, computer.getLevel(), Terminals.monitorBlocks(computer, side)); return true; }
        catch (NetworkControl.Failure e) { return false; }
    }

    private JsonObject describe(Peer peer, NetworkGrants.Grant<ServerComputer> grant) {
        var out = Attachment.describe(grant.target());
        out.addProperty("authorized", true);
        out.addProperty("attached", session.attachmentOf(grant.target()) != null);
        out.addProperty("grant_seconds_left", peer.grants.secondsLeft(grant));
        out.addProperty("permission", "allowed");
        return out;
    }

    /**
     * State of the player's own open computer menu, for the client's authorization icon. Only the in-game UI asks
     * for it: the local HTTP bridge has no route to this operation, and it never changes a grant.
     */
    private NetworkProtocol.Reply menuGrant(ServerPlayer player, Peer peer, JsonObject arguments) {
        var out = new JsonObject();
        var menu = player.containerMenu;
        var computer = menu.containerId == arguments.get("container").getAsInt() && menu instanceof ComputerMenu computerMenu
            && !player.isSpectator() ? computerMenu.getComputer() : null;
        out.addProperty("open", computer != null);
        out.addProperty("supported", computer != null && available(computer));
        var grant = computer == null ? null : peer.grants.live(this::available).stream()
            .filter(g -> g.target() == computer).findFirst().orElse(null);
        out.addProperty("authorized", grant != null);
        if (grant != null) out.addProperty("grant_seconds_left", peer.grants.secondsLeft(grant));
        return new NetworkProtocol.Reply(200, out);
    }

    /** Separate packet for a deliberate click in the current computer menu. Not reachable through local HTTP/RPC. */
    void authorize(ServerPlayer player, int containerId, boolean allow) {
        var peer = peers.get(player.getUUID());
        if (peer == null || peer.connection != player.connection || peer.epoch == null || !LiveConfig.NETWORK_ENABLED.get()) {
            message(player, "cctlive.network.not_ready", "[cct-live] 网络桥未就绪，请重新连接服务器。");
            return;
        }
        if (!peer.permit()) return;
        var menu = player.containerMenu;
        if (menu.containerId != containerId || !(menu instanceof ComputerMenu computerMenu) || player.isSpectator()) return;
        var computer = computerMenu.getComputer();
        if (!allow) {
            // Revoking only removes access, so it must work even when the computer is no longer usable or loaded.
            if (computer == null) {
                message(player, "cctlive.network.revoke_command", "[cct-live] 无法识别这台电脑，请输入 /cctlive network revoke <ID> 撤销。");
                return;
            }
            setGrant(player, peer, computer, false);
            return;
        }
        if (computer == null || !menu.stillValid(player) || !computer.checkUsable(player)) return;
        if (!available(computer)) {
            message(player, "cctlive.network.unsupported", "[cct-live] 平板电脑暂不支持网络授权，只能授权方块电脑和海龟。");
            return;
        }
        setGrant(player, peer, computer, true);
    }

    private void setGrant(ServerPlayer player, Peer peer, ServerComputer computer, boolean allow) {
        if (allow) {
            try { NetworkAuthorization.check(player, computer); }
            catch (NetworkControl.Failure e) {
                player.sendSystemMessage(Component.literal("[cct-live] " + e.getMessage()));
                return;
            }
        }
        if (allow) {
            try {
                peer.grants.live(this::available);
                peer.grants.allow(computer.getInstanceUUID(), computer.getID(), computer);
            } catch (IllegalArgumentException e) {
                message(player, "cctlive.network.limit", "[cct-live] 授权已达上限，请先撤销不需要的授权。");
                return;
            }
            message(player, "cctlive.network.allowed", "[cct-live] 已授权本机 AI 访问 %s 号电脑，30 分钟后失效。输入 /cctlive network revoke %s 可撤销。", computer.getID(), computer.getID());
        } else {
            revoke(player, peer, computer.getID());
            message(player, "cctlive.network.revoked", "[cct-live] 已撤销本机 AI 对 %s 号电脑的访问授权。", computer.getID());
        }
    }

    private Peer peer(ServerPlayer player) {
        var peer = peers.get(player.getUUID());
        if (peer == null || peer.connection != player.connection) {
            if (peer != null && peer.epoch != null) control.remove(owner(player, peer));
            peer = new Peer(player.connection);
            peers.put(player.getUUID(), peer);
        }
        return peer;
    }

    private boolean available(ServerComputer computer) {
        return ServerContext.get(session.server()).registry().get(computer.getInstanceUUID()) == computer
            && computer.getLevel().hasChunkAt(computer.getPosition())
            && computer.getLevel().getBlockEntity(computer.getPosition()) instanceof AbstractComputerBlockEntity block
            && block.getServerComputer() == computer;
    }

    private int authorizeNearby(ServerPlayer player, int id) {
        var peer = peers.get(player.getUUID());
        if (peer == null || peer.connection != player.connection || peer.epoch == null || !LiveConfig.NETWORK_ENABLED.get()) {
            message(player, "cctlive.network.not_ready", "[cct-live] 网络桥未就绪，请重新连接服务器。");
            return 0;
        }
        if (!peer.permit() || player.isSpectator()) return 0;
        var matches = session.computers().stream().filter(c -> c.getID() == id && available(c)).toList();
        if (matches.size() != 1) { message(player, "cctlive.network.nearby", "[cct-live] 需要唯一、已加载且在身边可使用的方块电脑。"); return 0; }
        var computer = matches.get(0);
        var pos = computer.getPosition();
        if (player.level() != computer.getLevel() || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64
            || !(computer.getLevel().getBlockEntity(pos) instanceof AbstractComputerBlockEntity block) || !block.isUsable(player) || !computer.checkUsable(player)) {
            message(player, "cctlive.network.nearby", "[cct-live] 需要唯一、已加载且在身边可使用的方块电脑。");
            return 0;
        }
        setGrant(player, peer, computer, true);
        return 1;
    }

    void disconnected(ServerPlayer player) {
        var peer = peers.get(player.getUUID());
        if (peer != null && peer.connection == player.connection) {
            if (peer.epoch != null) control.remove(owner(player, peer));
            peers.remove(player.getUUID());
        }
    }

    void prune() {
        if (!LiveConfig.NETWORK_ENABLED.get()) {
            control.clear();
            for (var peer : peers.values()) peer.grants.clear();
            releaseUnheld();
            return;
        }
        for (var entry : peers.entrySet()) {
            var player = session.server().getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.connection == entry.getValue().connection) refresh(player, entry.getValue());
            else entry.getValue().grants.clear();
        }
        control.invalidate(lease -> {
            var agent = control.agent(lease.agent());
            var peer = peers.get(agent.owner().player());
            if (peer == null || !agent.owner().connection().equals(peer.epoch)) return false;
            return peer.grants.live(this::available).stream().anyMatch(g -> g.id() == lease.computer() && g.instance().equals(lease.instance()));
        });
        releaseUnheld();
    }

    private void refresh(ServerPlayer player, Peer peer) {
        for (var grant : peer.grants.live(this::available)) {
            try { NetworkAuthorization.check(player, grant.target()); }
            catch (NetworkControl.Failure e) { revoke(player, peer, grant.id()); }
        }
    }

    private void revoke(ServerPlayer player, Peer peer, int id) {
        peer.grants.revoke(id);
        if (peer.epoch == null) return;
        var owner = owner(player, peer);
        control.invalidate(lease -> {
            var agent = control.agent(lease.agent());
            return lease.computer() != id || !agent.owner().equals(owner);
        });
    }

    void close() { control.clear(); peers.clear(); }

    private JsonObject leaseData(NetworkControl.Lease lease, UUID agent) {
        var out = new JsonObject();
        out.addProperty("held", lease != null);
        boolean mine = lease != null && lease.agent().equals(agent);
        out.addProperty("owned", mine);
        if (mine) {
            out.addProperty("lease_id", lease.id().toString());
            out.addProperty("lease_epoch", lease.epoch());
            out.addProperty("lease_seconds_left", control.secondsLeft(lease));
        }
        return out;
    }

    private static UUID uuid(JsonObject arguments, String key) { return UUID.fromString(arguments.get(key).getAsString()); }
    private static NetworkProtocol.Reply ok() { var out = new JsonObject(); out.addProperty("ok", true); return new NetworkProtocol.Reply(200, out); }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cctlive").then(Commands.literal("network")
            .then(Commands.literal("policy").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[cct-live] " + NetworkAuthorization.policy()), false);
                return 1;
            }))
            .then(Commands.literal("authorize").then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(context -> {
                var session = LiveSession.current();
                if (session == null) return 0;
                return session.network().authorizeNearby(context.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(context, "id"));
            })))
            .then(Commands.literal("list").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var session = LiveSession.current();
                if (session == null) return 0;
                var peer = session.network().peers.get(player.getUUID());
                if (peer == null || peer.connection != player.connection) return 0;
                session.network().refresh(player, peer);
                var grants = peer.grants.live(session.network()::available);
                if (grants.isEmpty()) message(player, "cctlive.network.none", "[cct-live] 当前没有网络授权。请打开电脑界面，点击授权 AI。");
                for (var grant : grants) message(player, "cctlive.network.entry", "[cct-live] %s 号电脑：有权限，授权剩余 %s 秒。", grant.id(), peer.grants.secondsLeft(grant));
                return grants.size();
            }))
            .then(Commands.literal("revoke").then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var id = IntegerArgumentType.getInteger(context, "id");
                var session = LiveSession.current();
                if (session == null) return 0;
                var peer = session.network().peers.get(player.getUUID());
                if (peer != null && peer.connection == player.connection) session.network().revoke(player, peer, id);
                message(player, "cctlive.network.revoked", "[cct-live] 已撤销本机 AI 对 %s 号电脑的访问授权。", id);
                return 1;
            })))));
    }

    private static void message(ServerPlayer player, String key, String fallback, Object... args) {
        player.sendSystemMessage(Component.translatableWithFallback(key, fallback, args));
    }
    private static JsonArray pair(int a, int b) { var out = new JsonArray(); out.add(a); out.add(b); return out; }
    private static JsonArray strings(String[] values) { var out = new JsonArray(); for (var value : values) out.add(value); return out; }
}

// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dan200.computercraft.shared.computer.core.ServerComputer;
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

    private static final class Peer {
        final Object connection;
        UUID epoch;
        final NetworkGrants<ServerComputer> grants = new NetworkGrants<>();
        long window = System.nanoTime();
        int requests;
        Peer(Object connection) { this.connection = connection; }
        boolean permit() {
            var now = System.nanoTime();
            if (now - window >= 1_000_000_000L) { window = now; requests = 0; }
            return ++requests <= 20;
        }
    }

    NetworkServer(LiveSession session) { this.session = session; }

    void handle(ServerPlayer player, String json, Consumer<NetworkProtocol.Frame> reply) {
        if (json.length() > NetworkProtocol.MAX_REQUEST_CHARS || !player.connection.isAcceptingMessages()) return;
        var peer = peer(player);
        if (!peer.permit()) return; // Bound both server work and response amplification per connection.
        UUID id, epoch;
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
            id = UUID.fromString(request.get("request_id").getAsString());
            epoch = UUID.fromString(request.get("epoch").getAsString());
        } catch (RuntimeException e) { return; }
        NetworkProtocol.Reply result;
        try {
            if (!LiveConfig.NETWORK_ENABLED.get()) result = NetworkProtocol.Reply.error(403, "network bridge disabled by server");
            else {
                var operation = request.get("operation").getAsString();
                var arguments = request.getAsJsonObject("arguments");
                if (operation.equals("hello")) result = hello(peer, epoch, arguments);
                else if (!epoch.equals(peer.epoch)) result = NetworkProtocol.Reply.error(409, "handshake required for this connection");
                else result = execute(peer, operation, arguments);
            }
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

    private NetworkProtocol.Reply hello(Peer peer, UUID epoch, JsonObject arguments) {
        if (!Integer.toString(NetworkProtocol.VERSION).equals(arguments.get("version").getAsString()))
            return NetworkProtocol.Reply.error(426, "incompatible cct-live protocol; server requires version " + NetworkProtocol.VERSION);
        if (!epoch.equals(peer.epoch)) peer.grants.clear();
        peer.epoch = epoch;
        return new NetworkProtocol.Reply(200, capabilities());
    }

    private static JsonObject capabilities() {
        var out = new JsonObject();
        out.addProperty("protocol", NetworkProtocol.VERSION);
        out.addProperty("transport", "minecraft-network");
        out.addProperty("read_only", true);
        out.addProperty("trace_available", false);
        var operations = new JsonArray();
        for (var name : List.of("computers", "status", "screen")) operations.add(name);
        out.add("capabilities", operations);
        return out;
    }

    private NetworkProtocol.Reply execute(Peer peer, String operation, JsonObject arguments) {
        if (operation.equals("capabilities")) return new NetworkProtocol.Reply(200, capabilities());
        if (operation.equals("computers")) {
            var out = capabilities();
            var computers = new JsonArray();
            for (var grant : peer.grants.live(this::available)) computers.add(describe(peer, grant));
            out.add("computers", computers);
            out.addProperty("tick", session.tickCount());
            out.addProperty("recording_mode", false);
            return new NetworkProtocol.Reply(200, out);
        }
        if (!Set.of("status", "screen").contains(operation))
            return NetworkProtocol.Reply.error(403, "operation unavailable in network preview");
        var idValue = arguments.get("computer");
        int id = idValue.getAsInt();
        if (id < 0 || !Integer.toString(id).equals(idValue.getAsString())) throw new IllegalArgumentException("invalid computer ID");
        NetworkGrants.Grant<ServerComputer> grant;
        try { grant = peer.grants.require(id, this::available); }
        catch (IllegalArgumentException e) { return NetworkProtocol.Reply.error(403, e.getMessage()); }
        var computer = grant.target();
        var source = arguments.has("source") ? arguments.get("source").getAsString() : "term";
        var terminal = Terminals.source(computer, source);
        if (terminal == null) return NetworkProtocol.Reply.error(404, "no authorized screen on side " + source);
        var snapshot = Terminals.snapshot(terminal);
        var out = describe(peer, grant);
        out.addProperty("tick", session.tickCount());
        out.addProperty("source", source);
        out.add("lines", Screens.compactLines(snapshot.text()));
        out.addProperty("screen_hash", ScreenHash.of(snapshot.width(), snapshot.height(), snapshot.text(), snapshot.fg(), snapshot.bg()));
        out.addProperty("trace_available", false);
        if (operation.equals("status")) {
            out.addProperty("last_seq", 0);
            out.add("new_since_after", new JsonObject());
            out.add("monitors", strings(Terminals.monitors(computer).keySet().toArray(String[]::new)));
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

    private JsonObject describe(Peer peer, NetworkGrants.Grant<ServerComputer> grant) {
        var out = Attachment.describe(grant.target());
        out.addProperty("authorized", true);
        out.addProperty("attached", false); // Observation grants do not attach or expose another user's trace.
        out.addProperty("grant_seconds_left", peer.grants.secondsLeft(grant));
        out.addProperty("permission", "observe");
        return out;
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
        if (menu.containerId != containerId || !(menu instanceof ComputerMenu computerMenu) || !menu.stillValid(player) || player.isSpectator()) return;
        var computer = computerMenu.getComputer();
        if (computer == null || !available(computer) || !computer.checkUsable(player)) return;
        if (allow && !LiveConfig.NETWORK_PLAYER_GRANTS.get() && !player.hasPermissions(2)) {
            message(player, "cctlive.network.denied", "[cct-live] 服务器仅允许管理员授权。服主可设置 network.allow_player_grants = true。");
            return;
        }
        if (allow) {
            try {
                peer.grants.live(this::available);
                peer.grants.allow(computer.getInstanceUUID(), computer.getID(), computer);
            } catch (IllegalArgumentException e) {
                message(player, "cctlive.network.limit", "[cct-live] 授权已达上限，请先撤销不需要的授权。");
                return;
            }
            message(player, "cctlive.network.allowed", "[cct-live] 已允许本机 AI 查看 %s 号电脑，30 分钟后失效。输入 /cctlive network revoke %s 可撤销。", computer.getID(), computer.getID());
        } else {
            peer.grants.revoke(computer.getID());
            message(player, "cctlive.network.revoked", "[cct-live] 已撤销本机 AI 对 %s 号电脑的查看权限。", computer.getID());
        }
    }

    private Peer peer(ServerPlayer player) {
        var peer = peers.get(player.getUUID());
        if (peer == null || peer.connection != player.connection) {
            peer = new Peer(player.connection);
            peers.put(player.getUUID(), peer);
        }
        return peer;
    }

    private boolean available(ServerComputer computer) {
        return ServerContext.get(session.server()).registry().get(computer.getInstanceUUID()) == computer;
    }

    void disconnected(ServerPlayer player) {
        var peer = peers.get(player.getUUID());
        if (peer != null && peer.connection == player.connection) peers.remove(player.getUUID());
    }

    void prune() {
        for (var peer : peers.values()) peer.grants.live(this::available);
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cctlive").then(Commands.literal("network")
            .then(Commands.literal("list").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var session = LiveSession.current();
                if (session == null) return 0;
                var peer = session.network().peers.get(player.getUUID());
                if (peer == null || peer.connection != player.connection) return 0;
                var grants = peer.grants.live(session.network()::available);
                if (grants.isEmpty()) message(player, "cctlive.network.none", "[cct-live] 当前没有网络查看授权。请打开电脑界面，点击允许 AI 查看。");
                for (var grant : grants) message(player, "cctlive.network.entry", "[cct-live] %s 号电脑：允许查看，剩余 %s 秒。", grant.id(), peer.grants.secondsLeft(grant));
                return grants.size();
            }))
            .then(Commands.literal("revoke").then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(context -> {
                var player = context.getSource().getPlayerOrException();
                var id = IntegerArgumentType.getInteger(context, "id");
                var session = LiveSession.current();
                if (session == null) return 0;
                var peer = session.network().peers.get(player.getUUID());
                if (peer != null && peer.connection == player.connection) peer.grants.revoke(id);
                message(player, "cctlive.network.revoked", "[cct-live] 已撤销本机 AI 对 %s 号电脑的查看权限。", id);
                return 1;
            })))));
    }

    private static void message(ServerPlayer player, String key, String fallback, Object... args) {
        player.sendSystemMessage(Component.translatableWithFallback(key, fallback, args));
    }
    private static JsonArray pair(int a, int b) { var out = new JsonArray(); out.add(a); out.add(b); return out; }
    private static JsonArray strings(String[] values) { var out = new JsonArray(); for (var value : values) out.add(value); return out; }
}

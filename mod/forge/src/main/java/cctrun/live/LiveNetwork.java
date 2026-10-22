// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.Consumer;

/** Forge 47 / Minecraft 1.20.1 transport only. */
final class LiveNetwork {
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(ResourceLocation.tryBuild("cctlive", "rpc"),
        () -> "1", LiveNetwork::compatibleTransport, LiveNetwork::compatibleTransport);
    // Client receiver is installed only on physical clients. No client class reference on a dedicated server.
    static Consumer<NetworkProtocol.Frame> receiver = frame -> {};

    record Request(String json) {}
    record Response(NetworkProtocol.Frame frame) {}
    record Permission(int containerId, boolean allow) {}

    private static boolean compatibleTransport(String version) {
        return "1".equals(version) || NetworkRegistry.ABSENT.version().equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder((packet, buffer) -> buffer.writeUtf(packet.json(), NetworkProtocol.MAX_REQUEST_CHARS))
            .decoder(buffer -> new Request(buffer.readUtf(NetworkProtocol.MAX_REQUEST_CHARS)))
            .consumerMainThread((packet, context) -> {
                var player = context.get().getSender();
                var session = LiveSession.current();
                if (player != null && session != null) session.network().handle(player, packet.json(), frame -> send(player, frame));
            }).add();
        CHANNEL.messageBuilder(Response.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder((packet, buffer) -> encode(buffer, packet.frame()))
            .decoder(buffer -> new Response(decode(buffer)))
            .consumerMainThread((packet, context) -> receiver.accept(packet.frame())).add();
        CHANNEL.messageBuilder(Permission.class, 2, NetworkDirection.PLAY_TO_SERVER)
            .encoder((packet, buffer) -> { buffer.writeVarInt(packet.containerId()); buffer.writeBoolean(packet.allow()); })
            .decoder(buffer -> new Permission(buffer.readVarInt(), buffer.readBoolean()))
            .consumerMainThread((packet, context) -> {
                var player = context.get().getSender();
                var session = LiveSession.current();
                if (player != null && session != null) session.network().authorize(player, packet.containerId(), packet.allow());
            }).add();
    }

    private static void send(ServerPlayer player, NetworkProtocol.Frame frame) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Response(frame));
    }

    private static void encode(FriendlyByteBuf buffer, NetworkProtocol.Frame frame) {
        buffer.writeUUID(frame.requestId());
        buffer.writeVarInt(frame.index());
        buffer.writeVarInt(frame.count());
        buffer.writeByteArray(frame.data());
    }

    private static NetworkProtocol.Frame decode(FriendlyByteBuf buffer) {
        return new NetworkProtocol.Frame(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readByteArray(NetworkProtocol.CHUNK_BYTES));
    }

    private LiveNetwork() {}
}

// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.function.Consumer;

/** NeoForge 21.1 / Minecraft 1.21.1 transport only. */
final class LiveNetwork {
    static Consumer<NetworkProtocol.Frame> receiver = frame -> {};

    record Request(String json) implements CustomPacketPayload {
        static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("cctlive", "request"));
        static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
            (buffer, packet) -> buffer.writeUtf(packet.json(), NetworkProtocol.MAX_REQUEST_CHARS),
            buffer -> new Request(buffer.readUtf(NetworkProtocol.MAX_REQUEST_CHARS)));
        @Override public Type<Request> type() { return TYPE; }
    }

    record Response(NetworkProtocol.Frame frame) implements CustomPacketPayload {
        static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("cctlive", "response"));
        static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.of((buffer, packet) -> {
            var frame = packet.frame();
            buffer.writeUUID(frame.requestId());
            buffer.writeVarInt(frame.index());
            buffer.writeVarInt(frame.count());
            buffer.writeByteArray(frame.data());
        }, buffer -> new Response(new NetworkProtocol.Frame(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readByteArray(NetworkProtocol.CHUNK_BYTES))));
        @Override public Type<Response> type() { return TYPE; }
    }

    record Permission(int containerId, boolean allow) implements CustomPacketPayload {
        static final Type<Permission> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("cctlive", "permission"));
        static final StreamCodec<RegistryFriendlyByteBuf, Permission> CODEC = StreamCodec.of((buffer, packet) -> {
            buffer.writeVarInt(packet.containerId());
            buffer.writeBoolean(packet.allow());
        }, buffer -> new Permission(buffer.readVarInt(), buffer.readBoolean()));
        @Override public Type<Permission> type() { return TYPE; }
    }

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(Request.TYPE, Request.CODEC, (packet, context) -> {
            var session = LiveSession.current();
            if (context.player() instanceof ServerPlayer player && session != null)
                session.network().handle(player, packet.json(), frame -> context.reply(new Response(frame)));
        });
        registrar.playToClient(Response.TYPE, Response.CODEC, (packet, context) -> receiver.accept(packet.frame()));
        registrar.playToServer(Permission.TYPE, Permission.CODEC, (packet, context) -> {
            var session = LiveSession.current();
            if (context.player() instanceof ServerPlayer player && session != null)
                session.network().authorize(player, packet.containerId(), packet.allow());
        });
    }

    private LiveNetwork() {}
}

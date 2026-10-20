// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkDirection;

@Mod.EventBusSubscriber(modid = CctLive.MOD_ID, value = Dist.CLIENT)
public final class LiveClient {
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        LiveNetwork.receiver = NetworkClientUi::receive;
        var connection = event.getConnection();
        NetworkClientUi.connected(FMLPaths.GAMEDIR.get(), json -> Minecraft.getInstance().execute(() -> {
            var listener = Minecraft.getInstance().getConnection();
            if (listener != null && listener.getConnection() == connection && connection.isConnected()
                && LiveNetwork.CHANNEL.isRemotePresent(connection))
                LiveNetwork.CHANNEL.sendTo(new LiveNetwork.Request(json), connection, NetworkDirection.PLAY_TO_SERVER);
        }));
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { NetworkClientUi.disconnected(); }

    @SubscribeEvent public static void screen(ScreenEvent.Init.Post event) {
        for (var button : NetworkClientUi.buttons(event.getScreen(), (id, allow) -> {
            var listener = Minecraft.getInstance().getConnection();
            if (listener != null && LiveNetwork.CHANNEL.isRemotePresent(listener.getConnection()))
                LiveNetwork.CHANNEL.sendTo(new LiveNetwork.Permission(id, allow), listener.getConnection(), NetworkDirection.PLAY_TO_SERVER);
        })) event.addListener(button);
    }

    private LiveClient() {}
}

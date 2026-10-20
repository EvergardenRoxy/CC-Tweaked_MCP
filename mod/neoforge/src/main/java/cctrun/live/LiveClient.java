// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;

@EventBusSubscriber(modid = CctLive.MOD_ID, value = Dist.CLIENT)
public final class LiveClient {
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        LiveNetwork.receiver = NetworkClientUi::receive;
        var connection = event.getConnection();
        NetworkClientUi.connected(FMLPaths.GAMEDIR.get(), json -> Minecraft.getInstance().execute(() -> {
            var listener = Minecraft.getInstance().getConnection();
            if (listener != null && listener.getConnection() == connection && connection.isConnected()
                && listener.hasChannel(LiveNetwork.Request.TYPE)) listener.send(new LiveNetwork.Request(json));
        }));
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { NetworkClientUi.disconnected(); }

    @SubscribeEvent public static void screen(ScreenEvent.Init.Post event) {
        for (var button : NetworkClientUi.buttons(event.getScreen(), (id, allow) -> {
            var listener = Minecraft.getInstance().getConnection();
            if (listener != null && listener.hasChannel(LiveNetwork.Permission.TYPE)) listener.send(new LiveNetwork.Permission(id, allow));
        })) event.addListener(button);
    }

    private LiveClient() {}
}

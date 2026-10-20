// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import dan200.computercraft.shared.computer.inventory.AbstractComputerMenu;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Loaded only on physical clients by each loader's client event subscriber. */
final class NetworkClientUi {
    private static NetworkClient current;

    static void connected(Path gameDir, Consumer<String> sender) {
        disconnected();
        current = new NetworkClient(sender);
        try { current.start(gameDir.resolve("cct-live/client")); }
        catch (IOException | RuntimeException e) {
            disconnected();
            CctLive.LOG.error("cct-live: could not start client bridge", e);
        }
    }

    static void disconnected() {
        if (current != null) current.close();
        current = null;
    }

    static void receive(NetworkProtocol.Frame frame) { if (current != null) current.receive(frame); }

    static List<Button> buttons(Screen screen, BiConsumer<Integer, Boolean> permission) {
        if (current == null || !(screen instanceof AbstractContainerScreen<?> container)
            || !(container.getMenu() instanceof AbstractComputerMenu)) return List.of();
        int id = container.getMenu().containerId;
        int x = Math.max(2, screen.width - 210);
        return List.of(
            Button.builder(Component.translatable("cctlive.network.allow_button"), button -> permission.accept(id, true))
                .bounds(x, 4, 100, 20).build(),
            Button.builder(Component.translatable("cctlive.network.revoke_button"), button -> permission.accept(id, false))
                .bounds(x + 104, 4, 100, 20).build());
    }

    private NetworkClientUi() {}
}

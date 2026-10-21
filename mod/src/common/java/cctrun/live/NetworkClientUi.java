// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.client.gui.widgets.TerminalWidget;
import dan200.computercraft.shared.computer.inventory.AbstractComputerMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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

    /**
     * One "AI" icon on CC:T computer, turtle and pocket computer screens, left of the GUI with its top flush with the
     * terminal (see NetworkButtonLayout). It shows this connection's grant for the open menu, asked from the server,
     * and a click toggles it through the separate permission packet.
     */
    static List<Button> buttons(Screen screen, BiConsumer<Integer, Boolean> permission) {
        if (current == null || !current.ready() || !(screen instanceof AbstractContainerScreen<?> container)
            || !(container.getMenu() instanceof AbstractComputerMenu)) return List.of();
        int id = container.getMenu().containerId;
        int terminalTop = screen.children().stream().filter(TerminalWidget.class::isInstance)
            .mapToInt(widget -> ((TerminalWidget) widget).getY()).findFirst().orElse(container.getGuiTop());
        var position = NetworkButtonLayout.place(container.getGuiLeft(), terminalTop);
        var client = current;
        var state = new GrantState[]{GrantState.UNKNOWN};
        var button = Button.builder(Component.literal("AI"), b -> {
                if (state[0] == GrantState.UNKNOWN || state[0] == GrantState.UNSUPPORTED) return;
                permission.accept(id, state[0] != GrantState.AUTHORIZED);
                // Same connection, so the server handles the click before this query.
                refresh(client, screen, id, b, state);
            })
            .bounds(position.x(), position.y(), NetworkButtonLayout.SIZE, NetworkButtonLayout.SIZE).build();
        show(button, GrantState.UNKNOWN, 0);
        refresh(client, screen, id, button, state);
        return List.of(button);
    }

    private enum GrantState { UNKNOWN, UNSUPPORTED, AUTHORIZED, NOT_AUTHORIZED }

    private static void refresh(NetworkClient client, Screen screen, int containerId, Button button, GrantState[] state) {
        var arguments = new JsonObject();
        arguments.addProperty("container", containerId);
        client.call("menu_grant", arguments).thenAccept(reply -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != screen) return;
            var data = reply.data();
            GrantState next;
            long seconds = 0;
            try {
                if (reply.status() != 200 || !data.get("open").getAsBoolean()) next = GrantState.UNKNOWN;
                else if (!data.get("supported").getAsBoolean()) next = GrantState.UNSUPPORTED;
                else if (data.get("authorized").getAsBoolean()) {
                    next = GrantState.AUTHORIZED;
                    seconds = data.get("grant_seconds_left").getAsLong();
                } else next = GrantState.NOT_AUTHORIZED;
            } catch (RuntimeException e) { next = GrantState.UNKNOWN; }
            state[0] = next;
            show(button, next, seconds);
        }));
    }

    private static void show(Button button, GrantState state, long seconds) {
        var color = switch (state) {
            case AUTHORIZED -> ChatFormatting.GREEN;
            case NOT_AUTHORIZED -> ChatFormatting.WHITE;
            default -> ChatFormatting.DARK_GRAY;
        };
        button.setMessage(Component.literal("AI").withStyle(color));
        button.active = state == GrantState.AUTHORIZED || state == GrantState.NOT_AUTHORIZED;
        button.setTooltip(Tooltip.create(switch (state) {
            case AUTHORIZED -> Component.translatable("cctlive.network.icon.authorized", Math.max(1, (seconds + 59) / 60));
            case NOT_AUTHORIZED -> Component.translatable("cctlive.network.icon.allow");
            case UNSUPPORTED -> Component.translatable("cctlive.network.icon.unsupported");
            case UNKNOWN -> Component.translatable("cctlive.network.icon.unknown");
        }));
    }

    private NetworkClientUi() {}
}

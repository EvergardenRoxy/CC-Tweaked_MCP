// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.shared.computer.core.ServerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * cct-live: lets an AI (through cct-run mcp) observe and control CC: Tweaked computers in a running game, on
 * demand. See mod/README.md.
 * <p>
 * Hook points (CC: Tweaked 1.120.2): {@link ServerContext#luaMachine} wraps every computer's Lua VM
 * ({@link LiveMachine}); the wrappers stay dormant until an AI attaches the computer (or recording mode is on).
 * Attached computers are polled every server tick ({@link Attachment}). Attaching and releasing are announced in
 * chat; {@code /cctlive list} and {@code /cctlive detach <id>} let players see and end attachments.
 */
@Mod(CctLive.MOD_ID)
public final class CctLive {
    public static final String MOD_ID = "cctlive";
    static final Logger LOG = LoggerFactory.getLogger("cct-live");

    @SuppressWarnings("removal") // ModLoadingContext.get() is how Forge 47 registers configs
    public CctLive() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, LiveConfig.SPEC);

        // Read by ServerContext's constructor, i.e. on every server start (each world in single player).
        ServerContext.luaMachine = LiveMachine.factory(ServerContext.luaMachine);
        // Every computer gets the __cctlive bridge used by rom/autorun/cctlive.lua (shipped in this jar's data pack).
        ComputerCraftAPI.registerAPIFactory(computer -> new BridgeApi());

        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        var home = FMLPaths.GAMEDIR.get().resolve("cct-live");
        try {
            var session = LiveSession.start(event.getServer(), home);
            LOG.info("cct-live CC:T capabilities: terminal={}, wired_query={}", Terminals.available(), PeripheralQuery.available());
            if (LiveConfig.HTTP_ENABLED.get()) session.startHttp(LiveConfig.HTTP_PORT.get());
        } catch (IOException e) {
            LOG.error("cct-live could not start; computers cannot be attached", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        LiveSession.stop();
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var session = LiveSession.current();
        if (session != null) session.tick();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        // Permission level 0: listing and ending AI attachments only takes control away from the AI.
        event.getDispatcher().register(Commands.literal("cctlive")
            .then(Commands.literal("list").executes(context -> {
                var session = LiveSession.current();
                var source = context.getSource();
                if (session == null || session.attachments().isEmpty()) {
                    source.sendSuccess(() -> Component.translatableWithFallback("cctlive.list.none", "[cct-live] 当前没有被 AI 接管的电脑"), false);
                    return 0;
                }
                for (var attachment : session.attachments()) {
                    var id = attachment.computer().getID();
                    var left = attachment.secondsLeft();
                    source.sendSuccess(() -> Component.translatableWithFallback("cctlive.list.entry", "[cct-live] %s 号电脑：AI 接管中，租约剩余 %s 秒", id, left), false);
                }
                return session.attachments().size();
            }))
            .then(Commands.literal("detach").then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(context -> {
                var id = IntegerArgumentType.getInteger(context, "id");
                var session = LiveSession.current();
                var computer = session == null ? null : session.find(id);
                if (computer == null || !session.detach(computer, "revoked")) {
                    context.getSource().sendFailure(Component.translatableWithFallback("cctlive.detach.none", "[cct-live] %s 号电脑没有被 AI 接管", id));
                    return 0;
                }
                return 1;
            }))));
    }

    /** A chat line for everyone on the server (single player: the player), and the log. */
    static void announce(MinecraftServer server, String key, String fallback, Object... args) {
        LOG.info("cct-live: " + String.format(fallback, args));
        if (!LiveConfig.CHAT.get()) return;
        var message = Component.translatableWithFallback(key, fallback, args).withStyle(ChatFormatting.YELLOW);
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(message, false));
    }
}

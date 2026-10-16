// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import dan200.computercraft.shared.computer.core.ServerContext;
import net.minecraftforge.common.MinecraftForge;
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
 * cct-live: observes CC: Tweaked computers from the Java side of a running Minecraft server and exposes what it
 * sees over a local HTTP endpoint. Prototype; see mod/README.md.
 * <p>
 * Hook points (CC: Tweaked 1.120.2):
 * <ul>
 *     <li>{@link ServerContext#luaMachine}: every computer's Lua VM is created through this factory, so wrapping
 *     it records each event delivered to the VM and each {@code peripheral.call} (see {@link LiveMachine}).</li>
 *     <li>{@code ServerContext.get(server).registry()}: polled every server tick for terminals, redstone output,
 *     power state and position (see {@link Watched}).</li>
 * </ul>
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

        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        var home = FMLPaths.GAMEDIR.get().resolve("cct-live");
        try {
            var session = LiveSession.start(event.getServer(), home);
            if (LiveConfig.HTTP_ENABLED.get()) session.startHttp(LiveConfig.HTTP_PORT.get());
        } catch (IOException e) {
            LOG.error("cct-live could not start; computers are not observed", e);
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
}

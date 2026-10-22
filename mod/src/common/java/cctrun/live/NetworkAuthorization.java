// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import dan200.computercraft.shared.computer.core.ServerComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import java.util.Collection;
import java.util.HashSet;

final class NetworkAuthorization {
    static void check(ServerPlayer player, ServerComputer computer) {
        if (player.isSpectator()) throw new NetworkControl.Failure(403, "spectators cannot authorize computers");
        if (!computer.checkUsable(player)) throw new NetworkControl.Failure(403, "CC:T computer access denied");
        if (!NetworkPlatform.ftbLoaded()) return;
        try { FtbNetworkAccess.check(player, computer); }
        catch (NetworkControl.Failure e) { throw e; }
        catch (RuntimeException | LinkageError e) {
            throw new NetworkControl.Failure(503, "FTB Chunks permission query failed; access unavailable");
        }
    }
    /**
     * A monitor's terminal is readable only where its blocks are: a computer the player may use cannot expose a
     * monitor that reaches into a claim they may not use. Claims are per chunk, so one block per chunk is checked.
     */
    static void checkBlocks(ServerPlayer player, Level level, Collection<BlockPos> blocks) {
        if (!NetworkPlatform.ftbLoaded()) return;
        var chunks = new HashSet<Long>();
        try {
            for (var pos : blocks) if (chunks.add(ChunkPos.asLong(pos))) FtbNetworkAccess.check(player, level, pos);
        }
        catch (NetworkControl.Failure e) { throw e; }
        catch (RuntimeException | LinkageError e) {
            throw new NetworkControl.Failure(503, "FTB Chunks permission query failed; access unavailable");
        }
    }

    static String policy() {
        if (!NetworkPlatform.ftbLoaded()) return "no claims integration; all players may authorize";
        try { return FtbNetworkAccess.ready() ? "FTB Chunks ready; effective interact AND edit" : "FTB Chunks not ready; access unavailable"; }
        catch (RuntimeException | LinkageError e) { return "FTB Chunks query failed; access unavailable"; }
    }

    private NetworkAuthorization() {}
}

// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import dan200.computercraft.shared.computer.core.ServerComputer;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;

/** Loaded only after checking the optional mod is present. Uses FTB's complete effective protection entry point. */
final class FtbNetworkAccess {
    static boolean ready() { return FTBChunksAPI.api().isManagerLoaded(); }

    static void check(ServerPlayer player, ServerComputer computer) { check(player, computer.getLevel(), computer.getPosition()); }

    /** The same effective check for any block, e.g. each part of a monitor the computer reads. */
    static void check(ServerPlayer player, Level level, BlockPos pos) {
        var api = FTBChunksAPI.api();
        NetworkAccess.check(new NetworkAccess.Claims() {
            public boolean ready() { return api.isManagerLoaded(); }
            public boolean claimed() { return api.getManager().getChunk(new ChunkDimPos(level, pos)) != null; }
            public boolean sameDimension() { return player.level() == level; }
            public boolean interact() { return !api.getManager().shouldPreventInteraction(player, InteractionHand.MAIN_HAND, pos, Protection.INTERACT_BLOCK, null); }
            public boolean edit() { return !api.getManager().shouldPreventInteraction(player, InteractionHand.MAIN_HAND, pos, Protection.EDIT_BLOCK, null); }
        });
    }
    private FtbNetworkAccess() {}
}

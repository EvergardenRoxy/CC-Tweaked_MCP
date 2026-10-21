// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import dan200.computercraft.shared.computer.core.ServerComputer;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** Loaded only after checking the optional mod is present. Uses FTB's complete effective protection entry point. */
final class FtbNetworkAccess {
    static boolean ready() { return FTBChunksAPI.api().isManagerLoaded(); }

    static void check(ServerPlayer player, ServerComputer computer) {
        var api = FTBChunksAPI.api();
        NetworkAccess.check(new NetworkAccess.Claims() {
            public boolean ready() { return api.isManagerLoaded(); }
            public boolean claimed() { return api.getManager().getChunk(new ChunkDimPos(computer.getLevel(), computer.getPosition())) != null; }
            public boolean sameDimension() { return player.level() == computer.getLevel(); }
            public boolean interact() { return !api.getManager().shouldPreventInteraction(player, InteractionHand.MAIN_HAND, computer.getPosition(), Protection.INTERACT_BLOCK, null); }
            public boolean edit() { return !api.getManager().shouldPreventInteraction(player, InteractionHand.MAIN_HAND, computer.getPosition(), Protection.EDIT_BLOCK, null); }
        });
    }
    private FtbNetworkAccess() {}
}

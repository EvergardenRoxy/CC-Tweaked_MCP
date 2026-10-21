// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import dan200.computercraft.shared.computer.core.ServerComputer;
import net.minecraft.server.level.ServerPlayer;

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
    static String policy() {
        if (!NetworkPlatform.ftbLoaded()) return "no claims integration; all players may authorize";
        try { return FtbNetworkAccess.ready() ? "FTB Chunks ready; effective interact AND edit" : "FTB Chunks not ready; access unavailable"; }
        catch (RuntimeException | LinkageError e) { return "FTB Chunks query failed; access unavailable"; }
    }

    private NetworkAuthorization() {}
}

// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import net.neoforged.fml.ModList;

final class NetworkPlatform {
    static boolean ftbLoaded() { return ModList.get().isLoaded("ftbchunks"); }
    private NetworkPlatform() {}
}

// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/cctlive-common.toml */
final class LiveConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue HTTP_ENABLED;
    static final ForgeConfigSpec.IntValue HTTP_PORT;
    static final ForgeConfigSpec.BooleanValue TRACE_SCREENS;
    static final ForgeConfigSpec.IntValue TRACE_MAX_MB;

    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("http");
        HTTP_ENABLED = b.comment("Serve the local HTTP endpoint (always bound to 127.0.0.1; requests need the token in cct-live/token.txt).")
            .define("enabled", true);
        HTTP_PORT = b.comment("Port of the local HTTP endpoint.")
            .defineInRange("port", 47823, 1024, 65535);
        b.pop();
        b.push("trace");
        TRACE_SCREENS = b.comment("Record terminal changes (text and colours) once per tick.")
            .define("screens", true);
        TRACE_MAX_MB = b.comment("Stop writing a computer's trace file once it reaches this size (a 'truncated' record marks the cut).")
            .defineInRange("max_megabytes_per_computer", 64, 1, 4096);
        b.pop();
        SPEC = b.build();
    }

    private LiveConfig() {
    }
}

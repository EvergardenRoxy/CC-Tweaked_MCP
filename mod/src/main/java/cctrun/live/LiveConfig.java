// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/cctlive-common.toml. Read when a world opens; changes apply from the next world load. */
final class LiveConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue HTTP_ENABLED;
    static final ForgeConfigSpec.IntValue HTTP_PORT;
    static final ForgeConfigSpec.BooleanValue TRACE_SCREENS;
    static final ForgeConfigSpec.IntValue TRACE_MAX_MB;
    static final ForgeConfigSpec.IntValue TRACE_MAX_STRING;
    static final ForgeConfigSpec.BooleanValue RECORDER_ENABLED;
    static final ForgeConfigSpec.IntValue RECORDER_SIZE;
    static final ForgeConfigSpec.IntValue LEASE_SECONDS;
    static final ForgeConfigSpec.BooleanValue CHAT;

    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("http");
        HTTP_ENABLED = b.comment("Serve the local HTTP endpoint (always bound to 127.0.0.1; requests need the token in cct-live/token.txt).")
            .define("enabled", true);
        HTTP_PORT = b.comment("Port of the local HTTP endpoint.")
            .defineInRange("port", 47823, 1024, 65535);
        b.pop();

        b.push("trace");
        TRACE_SCREENS = b.comment("While a computer is attached, record terminal changes (text and colours) once per tick.")
            .define("screens", true);
        TRACE_MAX_MB = b.comment("Stop writing a computer's trace file (one per computer per world session) once it reaches this size (a 'truncated' record marks the cut).")
            .defineInRange("max_megabytes_per_computer", 64, 1, 4096);
        TRACE_MAX_STRING = b.comment("Characters kept of each string in a record (file and HTTP data, event arguments ...). Whole files can be read with the files endpoint.")
            .defineInRange("max_string", 200, 50, 65536);
        b.pop();

        b.push("recorder");
        RECORDER_ENABLED = b.comment(
                "Recording mode. Off (default): computers that no AI has attached are not observed at all.",
                "On: every computer keeps its most recent records (events, peripheral calls, http and file access; no screens) in memory,",
                "so an AI that attaches later also sees what happened just before. Costs a little time on every event and call.")
            .define("enabled", false);
        RECORDER_SIZE = b.comment("Records kept per computer in recording mode.")
            .defineInRange("size", 256, 16, 10000);
        b.pop();

        b.push("control");
        LEASE_SECONDS = b.comment("An attachment ends by itself when the AI sends no request for this computer for this long (seconds).")
            .defineInRange("lease_seconds", 1800, 30, 86400);
        CHAT = b.comment("Announce in chat when an AI attaches to or releases a computer.")
            .define("chat", true);
        b.pop();
        SPEC = b.build();
    }

    private LiveConfig() {
    }
}

// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/cctlive-common.toml. Read when a world opens; changes apply from the next world load. */
final class LiveConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue HTTP_ENABLED;
    static final ForgeConfigSpec.BooleanValue NETWORK_ENABLED;
    static final ForgeConfigSpec.IntValue NETWORK_LEASE_SECONDS;
    static final ForgeConfigSpec.IntValue NETWORK_IDLE_SECONDS;
    static final ForgeConfigSpec.IntValue HTTP_PORT;
    static final ForgeConfigSpec.BooleanValue TRACE_SCREENS;
    static final ForgeConfigSpec.IntValue TRACE_MAX_MB;
    static final ForgeConfigSpec.IntValue TRACE_MAX_STRING;
    static final ForgeConfigSpec.BooleanValue RECORDER_ENABLED;
    static final ForgeConfigSpec.IntValue RECORDER_SIZE;
    static final ForgeConfigSpec.IntValue LEASE_SECONDS;
    static final ForgeConfigSpec.BooleanValue CHAT;
    static final ForgeConfigSpec.BooleanValue FILES_WRITE;

    static {
        var b = new ForgeConfigSpec.Builder();
        b.push("network");
        NETWORK_ENABLED = b.comment("Allow authorized players to control computers from their own AI through the Minecraft connection (files, Lua, input, power, trace). Writing files also needs files.allow_write. No additional server port is opened.")
            .define("enabled", true);
        NETWORK_LEASE_SECONDS = b.comment("MCP session and exclusive lease timeout. Each MCP process must heartbeat; native CC interfaces are unaffected.")
            .defineInRange("lease_seconds", 60, 30, 300);
        NETWORK_IDLE_SECONDS = b.comment("Release MCP control after this many seconds without target requests. Heartbeats do not count as activity.")
            .defineInRange("idle_seconds", 300, 300, 86400);
        b.pop();

        b.push("http");
        HTTP_ENABLED = b.comment("Serve the local HTTP endpoint (always bound to 127.0.0.1; requests need the token in cct-live/token.txt).")
            .define("enabled", true);
        HTTP_PORT = b.comment("Port of the local HTTP endpoint.")
            .defineInRange("port", 47823, 1024, 65535);
        b.pop();

        b.push("trace");
        TRACE_SCREENS = b.comment("While a computer is attached, record terminal changes (text and colours) once per tick.")
            .define("screens", true);
        TRACE_MAX_MB = b.comment("Size kept of a computer's trace (one per computer per world session). The newest records are kept: the trace is split into two files of half this size, and the older one is dropped when the newer one is full (a 'trace_rotated' record marks each switch).")
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

        b.push("files");
        FILES_WRITE = b.comment(
                "Let an attached AI WRITE files on the computer (create, overwrite or append; cct_live_files op=write).",
                "Off by default. When on, the AI can change any file of a computer it has attached, including startup.lua,",
                "within the computer's disk space. Every write is recorded in the trace as a 'file_write' record.",
                "Reading files is always possible for an attached computer and is not affected by this switch.",
                "允许已接管电脑的 AI 写入文件（新建、覆盖或追加）。默认关闭。打开后 AI 可以修改该电脑上的任何文件（包括 startup.lua），",
                "每次写入都会在 trace 里留下 file_write 记录。读取文件不受此开关影响。")
            .define("allow_write", false);
        b.pop();
        SPEC = b.build();
    }

    private LiveConfig() {
    }
}

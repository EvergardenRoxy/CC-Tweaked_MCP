// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

/** Version strings written into run summaries. */
public final class Versions {
    /** The tool itself. */
    public static final String TOOL = "0.1.0";
    /** The CC: Tweaked core this tool is built against (tag v1.20.1-1.120.2). */
    public static final String CC_TWEAKED = "1.120.2";
    public static final String MINECRAFT = "1.20.1";

    public static final String SUMMARY_SCHEMA = "cct-run/summary@1";
    public static final String TRACE_SCHEMA = "cct-run/trace@1";
    public static final String FIXTURE_SCHEMA = "cct-run/fixture@1";

    private Versions() {
    }
}

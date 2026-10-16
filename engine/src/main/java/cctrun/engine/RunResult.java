// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * Outcome of one run.
 *
 * @param status   ok | error | timeout | shutdown | reboot
 * @param exitCode 0 ok, 1 error, 2 timeout, 3 shutdown or reboot by the program
 * @param runDir   directory holding summary.json, trace.jsonl and the inputs
 * @param summary  contents of summary.json
 */
public record RunResult(String status, int exitCode, Path runDir, JsonObject summary) {
}

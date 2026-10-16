// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

/** A fixture file is malformed. The message names the offending JSON path. */
public final class FixtureException extends Exception {
    public FixtureException(String path, String message) {
        super(path + ": " + message);
    }
}

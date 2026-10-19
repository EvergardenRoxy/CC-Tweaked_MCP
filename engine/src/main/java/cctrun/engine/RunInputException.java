// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;
import java.io.IOException;
/** A validated run's supplied files do not fit its simulated disk. */
public final class RunInputException extends IOException {
    public RunInputException(String message, Throwable cause) { super(message, cause); }
}

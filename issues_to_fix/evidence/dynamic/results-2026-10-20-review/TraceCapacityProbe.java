// SPDX-License-Identifier: MPL-2.0
import cctrun.engine.Clock;
import cctrun.engine.Trace;
import com.google.gson.JsonObject;
import java.nio.file.Path;

/** Public-writer probe only; does not start Minecraft or access a player world. */
public final class TraceCapacityProbe {
    public static void main(String[] args) throws Exception {
        try (var trace = new Trace(Path.of(args[0]), new Clock())) {
            var data = new JsonObject();
            data.addProperty("text", "x".repeat(4096));
            for (int i = 0; i < 512; i++) trace.record("review_probe", data);
        }
    }
}

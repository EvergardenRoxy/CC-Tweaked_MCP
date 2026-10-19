// SPDX-License-Identifier: MPL-2.0
package cctrun.engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

class RunStateTest {
    @TempDir Path parent;
    @Test void independentReservationsAndNormalRunStatesNeverOverwriteEachOther() throws Exception {
        var a = RunState.reserve(parent);
        var b = RunState.reserve(parent);
        assertNotEquals(a, b);
        RunState.update(a, "failed", "cancelled");
        assertEquals("starting", JsonParser.parseString(Files.readString(b.resolve("state.json"))).getAsJsonObject().get("state").getAsString());
        var result = RunHelper.run(parent, "print('done')", null, 10);
        var state = JsonParser.parseString(Files.readString(result.runDir().resolve("state.json"))).getAsJsonObject();
        assertEquals("finished", state.get("state").getAsString());
        assertEquals("ok", state.get("reason").getAsString());
        assertTrue(state.has("pid_started_at"));
    }
}

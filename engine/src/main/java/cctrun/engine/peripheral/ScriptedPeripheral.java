// SPDX-License-Identifier: MPL-2.0

package cctrun.engine.peripheral;

import cctrun.engine.JsonValues;
import cctrun.engine.Trace;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.LuaException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A peripheral from any mod, described by the fixture instead of modelled: its type name and, per method, the values
 * it returns ({@code returns}, a {@code sequence} of returns, or an {@code error}). A setter can store its first
 * argument as another method's return value ({@code sets}), and the timeline can change any method's returns. No
 * behaviour of the real block is simulated: the values are whatever the fixture says (taken from the game, e.g. with
 * cct_live_peripheral or peripheral_return records).
 */
public final class ScriptedPeripheral extends DispatchPeripheral {
    /** What one method does; changed by setters and by the timeline while a run goes on. */
    public static final class Behaviour {
        private Object[] returns = new Object[0];
        private List<Object[]> sequence;
        private int next;
        private String error;
        private String sets;

        synchronized Object[] call(Map<String, Behaviour> all, Object[] args) throws LuaException {
            if (sets != null) {
                var target = all.get(sets);
                synchronized (target) {
                    target.sequence = null;
                    target.error = null;
                    target.returns = new Object[]{args.length > 0 ? args[0] : null};
                }
            }
            if (error != null) throw new LuaException(error);
            if (sequence != null) {
                var out = sequence.get(Math.min(next, sequence.size() - 1));
                next++;
                return out;
            }
            return returns;
        }

        /** Replace what the method does (fixture or timeline spec: a plain value, or returns / sequence / error). */
        public synchronized void set(JsonElement spec) {
            returns = new Object[0];
            sequence = null;
            next = 0;
            error = null;
            if (!spec.isJsonObject()) {
                returns = new Object[]{JsonValues.toLua(spec)};
                return;
            }
            var o = spec.getAsJsonObject();
            if (o.has("returns")) returns = values(o.getAsJsonArray("returns"));
            if (o.has("sequence")) {
                sequence = new ArrayList<>();
                for (var step : o.getAsJsonArray("sequence")) sequence.add(values(step.getAsJsonArray()));
            }
            if (o.has("error")) error = o.get("error").getAsString();
        }

        private static Object[] values(JsonArray array) {
            var out = new Object[array.size()];
            for (var i = 0; i < out.length; i++) out[i] = JsonValues.toLua(array.get(i));
            return out;
        }
    }

    private final Map<String, Behaviour> behaviours = new LinkedHashMap<>();

    /** {@code spec} is validated by Fixture: peripheral_type, additional_types, main_thread, methods. */
    public ScriptedPeripheral(JsonObject spec, Trace trace) {
        super(spec.get("peripheral_type").getAsString(), additionalTypes(spec), trace);
        var mainThread = !spec.has("main_thread") || spec.get("main_thread").getAsBoolean();
        var methods = spec.getAsJsonObject("methods");
        for (var entry : methods.entrySet()) {
            var behaviour = new Behaviour();
            behaviour.set(entry.getValue());
            var value = entry.getValue();
            var methodMainThread = mainThread;
            if (value.isJsonObject()) {
                var o = value.getAsJsonObject();
                if (o.has("sets")) behaviour.sets = o.get("sets").getAsString();
                if (o.has("main_thread")) methodMainThread = o.get("main_thread").getAsBoolean();
            }
            behaviours.put(entry.getKey(), behaviour);
            method(methodMainThread, (c, ctx, a) -> behaviour.call(behaviours, a.getAll()), entry.getKey());
        }
    }

    private static java.util.Set<String> additionalTypes(JsonObject spec) {
        var out = new LinkedHashSet<String>();
        if (spec.has("additional_types")) for (var t : spec.getAsJsonArray("additional_types")) out.add(t.getAsString());
        return out;
    }

    /** The methods' behaviours by name, for the timeline. */
    @Override
    public Map<String, Behaviour> getTarget() {
        return behaviours;
    }
}

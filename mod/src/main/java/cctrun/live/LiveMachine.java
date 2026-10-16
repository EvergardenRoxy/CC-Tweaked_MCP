// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.core.lua.ILuaMachine;
import dan200.computercraft.core.lua.MachineEnvironment;
import dan200.computercraft.core.lua.MachineResult;
import dan200.computercraft.core.metrics.MetricsObserver;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a computer's real Lua VM (as cctrun.engine.RecordingMachine does in cct-run) and records every event
 * delivered to it, VM errors, and VM start and close. Peripheral calls are recorded by {@link TracingMethods},
 * which this factory installs into the VM's method lookup.
 * <p>
 * The VM does not know which computer it belongs to. {@link MachineEnvironment#metrics()} is the computer's own
 * {@code ServerComputer.getMetrics()}, so {@link Recorder} matches it against the computers {@link LiveSession}
 * has seen; records made before the match (normally none) are held back and written once it succeeds.
 */
final class LiveMachine implements ILuaMachine {
    static ILuaMachine.Factory factory(ILuaMachine.Factory inner) {
        return (environment, bios) -> {
            var session = LiveSession.current();
            if (session == null) return inner.create(environment, bios);

            var recorder = new Recorder(session, environment.metrics());
            var traced = new MachineEnvironment(
                environment.context(), environment.metrics(), environment.timeout(), environment.apis(),
                new TracingMethods(environment.luaMethods(), recorder), environment.hostString()
            );
            var machine = new LiveMachine(inner.create(traced, bios), recorder);
            recorder.record("vm_start", new JsonObject());
            return machine;
        };
    }

    private final ILuaMachine inner;
    private final Recorder recorder;

    private LiveMachine(ILuaMachine inner, Recorder recorder) {
        this.inner = inner;
        this.recorder = recorder;
    }

    @Override
    public MachineResult handleEvent(String eventName, Object[] arguments) {
        // Recorded before the VM runs, so whatever the event causes (peripheral calls, a main-thread result
        // finishing) follows it in the trace. The first resume (eventName == null) boots the BIOS; not an event.
        if (eventName != null) {
            var data = new JsonObject();
            data.addProperty("name", eventName);
            noteHttpHandle(eventName, arguments);
            data.add("args", JsonValues.array(arguments, recorder.handles()));
            recorder.record("event", data);
        }
        var result = inner.handleEvent(eventName, arguments);
        if (result.isError()) {
            var data = new JsonObject();
            data.addProperty("message", result.getMessage());
            data.addProperty("timeout", result == MachineResult.TIMEOUT);
            recorder.record("vm_error", data);
        }
        return result;
    }

    /** http_success (url, handle) and http_failure (url, message, handle?): remember which URL a response is for. */
    private void noteHttpHandle(String eventName, Object[] arguments) {
        if (arguments == null || arguments.length < 2 || !(arguments[0] instanceof String url)) return;
        if (eventName.equals("http_success")) recorder.handles().http(arguments[1], url, "http_response");
        else if (eventName.equals("http_failure") && arguments.length >= 3 && arguments[2] != null) recorder.handles().http(arguments[2], url, "http_response");
        else if (eventName.equals("websocket_success")) recorder.handles().http(arguments[1], url, "websocket");
    }

    @Override
    public void printExecutionState(StringBuilder out) {
        inner.printExecutionState(out);
    }

    @Override
    public void close() {
        inner.close();
        recorder.record("vm_close", new JsonObject());
    }

    /** Finds this VM's computer trace, holding records back until the computer is known. */
    static final class Recorder {
        private static final int MAX_PENDING = 1000;

        private record Pending(long tick, double ms, String kind, JsonObject data) {
        }

        private final LiveSession session;
        private final MetricsObserver metrics;
        private volatile Watched watched;
        private final List<Pending> pending = new ArrayList<>();
        private final Handles handles = new Handles();
        private int dropped;

        Recorder(LiveSession session, MetricsObserver metrics) {
            this.session = session;
            this.metrics = metrics;
        }

        Handles handles() {
            return handles;
        }

        long nextCallId() {
            var w = resolve();
            return w == null ? -1 : w.trace().nextCallId();
        }

        void record(String kind, JsonObject data) {
            var w = resolve();
            if (w != null) {
                w.trace().record(kind, data);
                return;
            }
            synchronized (pending) {
                if (pending.size() < MAX_PENDING) pending.add(new Pending(session.tickCount(), session.millis(), kind, data));
                else dropped++;
            }
        }

        private Watched resolve() {
            var w = watched;
            if (w != null) return w;
            w = session.watchedFor(metrics);
            if (w == null) return null;
            synchronized (pending) {
                for (var p : pending) w.trace().recordAt(p.tick, p.ms, p.kind, p.data);
                if (dropped > 0) {
                    var data = new JsonObject();
                    data.addProperty("dropped", dropped);
                    w.trace().record("records_dropped", data);
                }
                pending.clear();
                watched = w;
            }
            return w;
        }
    }
}

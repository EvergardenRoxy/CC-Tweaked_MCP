// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.core.lua.ILuaMachine;
import dan200.computercraft.core.lua.MachineEnvironment;
import dan200.computercraft.core.lua.MachineResult;
import dan200.computercraft.core.metrics.MetricsObserver;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a computer's real Lua VM (as cctrun.engine.RecordingMachine does in cct-run). Installed into every VM when
 * it is created, because CC: Tweaked offers no later hook point, but dormant until needed: while no AI has
 * attached the computer and recording mode is off, each event and each instrumented call costs one check
 * ({@link Recorder#active()}) and nothing is converted or written.
 * <p>
 * When active it records every event delivered to the VM, VM errors, and VM start and close; {@link TracingMethods}
 * (installed into the VM's method lookup) records peripheral, http and file access.
 * <p>
 * The VM does not know its computer. {@link MachineEnvironment#metrics()} is the computer's own
 * {@code ServerComputer.getMetrics()} (stable across reboots), which is how attachments and recording rings are
 * looked up.
 */
final class LiveMachine implements ILuaMachine {
    static ILuaMachine.Factory factory(ILuaMachine.Factory inner) {
        return (environment, bios) -> {
            var recorder = new Recorder(environment.metrics());
            for (var api : environment.apis()) if (api instanceof BridgeApi bridge) bridge.connect(recorder);
            var traced = new MachineEnvironment(
                environment.context(), environment.metrics(), environment.timeout(), environment.apis(),
                new TracingMethods(environment.luaMethods(), recorder), environment.hostString()
            );
            var machine = new LiveMachine(inner.create(traced, bios), recorder);
            if (recorder.active()) recorder.record("vm_start", new JsonObject());
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
        var active = recorder.active();
        if (active && eventName != null) {
            var data = new JsonObject();
            data.addProperty("name", eventName);
            noteHttpHandle(eventName, arguments);
            data.add("args", JsonValues.array(arguments, recorder.handles()));
            recorder.record("event", data);
        }
        var result = inner.handleEvent(eventName, arguments);
        if (result.isError() && (active || recorder.active())) {
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
        if (recorder.active()) recorder.record("vm_close", new JsonObject());
    }

    /** Where this VM's records go: the computer's attachment trace, else its recording ring, else nowhere. */
    static final class Recorder {
        private final MetricsObserver metrics;
        private final Handles handles = new Handles();
        private final AtomicLong callIds = new AtomicLong();

        Recorder(MetricsObserver metrics) {
            this.metrics = metrics;
        }

        Handles handles() {
            return handles;
        }

        /** Cheap: two volatile reads while nothing is attached and recording mode is off. */
        boolean active() {
            var session = LiveSession.current();
            return session != null && (session.recording() || session.attachedTo(metrics) != null);
        }

        /** Pairs a peripheral_call with its peripheral_return; per VM, so it also works in the recording ring. */
        long nextCallId() {
            return callIds.incrementAndGet();
        }

        /** While attached (not in recording mode): saves an HTTP response body in full; returns its number or -1. */
        int saveBody(Object value) {
            var session = LiveSession.current();
            var attachment = session == null ? null : session.attachedTo(metrics);
            if (attachment == null) return -1;
            byte[] bytes;
            if (value instanceof java.nio.ByteBuffer b) {
                var copy = b.duplicate();
                bytes = new byte[copy.remaining()];
                copy.get(bytes);
            } else if (value instanceof byte[] b) {
                bytes = b;
            } else if (value instanceof String s) {
                bytes = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            } else {
                return -1;
            }
            return attachment.trace().saveBody(bytes);
        }

        void record(String kind, JsonObject data) {
            var session = LiveSession.current();
            if (session == null) return;
            var attachment = session.attachedTo(metrics);
            if (attachment != null) attachment.trace().record(kind, data);
            else if (session.recording()) session.ring(metrics).add(session.tickCount(), session.millis(), kind, data);
        }
    }
}

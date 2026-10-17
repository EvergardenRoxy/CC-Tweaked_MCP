// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.core.apis.FSAPI;
import dan200.computercraft.core.apis.HTTPAPI;
import dan200.computercraft.core.apis.PeripheralAPI;
import dan200.computercraft.core.methods.LuaMethod;
import dan200.computercraft.core.methods.MethodSupplier;

import java.util.Set;

/**
 * The VM's method lookup with selected Java methods instrumented. Each wrapper first asks
 * {@link LiveMachine.Recorder#active()} and, while the computer is neither attached nor in recording mode, calls
 * straight through. Every Java object Lua can call (the global APIs
 * and every value returned to Lua, such as file and HTTP response handles) gets its methods from here.
 * <ul>
 *     <li>{@code peripheral.call}: {@code peripheral_call} + {@code peripheral_return} (as in cct-run). Every
 *     peripheral access ends here: the ROM's {@code peripheral.wrap}/{@code find} build their methods from
 *     {@code peripheral.call}. Main-thread methods yield and finish on a later tick; their return is recorded when
 *     the callback chain finishes.</li>
 *     <li>{@code http.request/checkURL/websocket} and {@code fs.open/delete/move/copy/makeDir}: one {@code api}
 *     record per call (api, method, args, ok, result or error); files under /rom are skipped. {@code http.get}/{@code post} in the ROM call
 *     {@code http.request}; the response arrives as an {@code http_success} event.</li>
 *     <li>Methods of handles (open files, HTTP responses, websockets): one {@code handle} record per call (handle,
 *     method, args, ok, result or error), so reads and writes show their data (first 200 characters).</li>
 * </ul>
 */
final class TracingMethods implements MethodSupplier<LuaMethod> {
    private static final Set<String> HTTP = Set.of("request", "checkURL", "websocket");
    private static final Set<String> FS = Set.of("open", "delete", "move", "copy", "makeDir");

    private final MethodSupplier<LuaMethod> inner;
    private final LiveMachine.Recorder recorder;

    TracingMethods(MethodSupplier<LuaMethod> inner, LiveMachine.Recorder recorder) {
        this.inner = inner;
        this.recorder = recorder;
    }

    private static boolean traced(Object object) {
        return object instanceof PeripheralAPI || object instanceof HTTPAPI || object instanceof FSAPI || Handles.isHandle(object);
    }

    @Override
    public boolean forEachSelfMethod(Object object, UntargetedConsumer<LuaMethod> consumer) {
        if (!traced(object)) return inner.forEachSelfMethod(object, consumer);
        return inner.forEachSelfMethod(object, (name, method, info) -> consumer.accept(name, wrap(object, name, method), info));
    }

    @Override
    public boolean forEachMethod(Object object, TargetedConsumer<LuaMethod> consumer) {
        if (!traced(object)) return inner.forEachMethod(object, consumer);
        // For an HTTP response the read methods live on an extra object (the body reader); they are recorded
        // against the response, which is what Lua holds.
        return inner.forEachMethod(object, (target, name, method, info) -> consumer.accept(target, name, wrap(object, name, method), info));
    }

    private LuaMethod wrap(Object owner, String name, LuaMethod method) {
        if (owner instanceof PeripheralAPI) return name.equals("call") ? new PeripheralCall(method) : method;
        if (owner instanceof HTTPAPI) return HTTP.contains(name) ? new ApiCall("http", name, method) : method;
        if (owner instanceof FSAPI) return FS.contains(name) ? new ApiCall("fs", name, method) : method;
        return new HandleCall(owner, name, method);
    }

    /** {@code api}: http.* and fs.* calls; all of them return immediately. */
    private final class ApiCall implements LuaMethod {
        private final String api;
        private final String name;
        private final LuaMethod inner;

        ApiCall(String api, String name, LuaMethod inner) {
            this.api = api;
            this.name = name;
            this.inner = inner;
        }

        @Override
        public MethodResult apply(Object target, ILuaContext context, IArguments args) throws LuaException {
            if (!recorder.active()) return inner.apply(target, context, args);
            var values = args.getAll();
            if (api.equals("fs") && values.length > 0 && Handles.isRomPath(values[0])) {
                // Still register ROM handles, so their reads are recognised and skipped too.
                var result = inner.apply(target, context, args);
                var returned = result.getResult();
                if (name.equals("open") && returned != null && returned.length > 0 && Handles.isHandle(returned[0])) {
                    recorder.handles().file(returned[0], String.valueOf(values[0]), String.valueOf(values.length > 1 ? values[1] : null));
                }
                return result;
            }
            var data = new JsonObject();
            data.addProperty("api", api);
            data.addProperty("method", name);
            data.add("args", JsonValues.array(values, recorder.handles()));
            MethodResult result;
            try {
                result = inner.apply(target, context, args);
            } catch (LuaException e) {
                data.addProperty("ok", false);
                data.addProperty("error", e.getMessage());
                recorder.record("api", data);
                throw e;
            }
            var returned = result.getResult();
            // fs.open returns (handle) or (nil, message): name the handle after the file.
            if (api.equals("fs") && name.equals("open") && returned != null && returned.length > 0 && Handles.isHandle(returned[0])) {
                recorder.handles().file(returned[0], String.valueOf(values.length > 0 ? values[0] : null), String.valueOf(values.length > 1 ? values[1] : null));
            }
            data.addProperty("ok", true);
            data.add("result", JsonValues.array(returned, recorder.handles()));
            recorder.record("api", data);
            return result;
        }
    }

    /** {@code handle}: methods of files, HTTP responses and websockets. */
    private final class HandleCall implements LuaMethod {
        private final Object owner;
        private final String name;
        private final LuaMethod inner;

        HandleCall(Object owner, String name, LuaMethod inner) {
            this.owner = owner;
            this.name = name;
            this.inner = inner;
        }

        @Override
        public MethodResult apply(Object target, ILuaContext context, IArguments args) throws LuaException {
            if (!recorder.active() || recorder.handles().isRomFile(owner)) return inner.apply(target, context, args);
            var data = new JsonObject();
            var handle = recorder.handles().describe(owner);
            for (var entry : handle.entrySet()) data.add(entry.getKey(), entry.getValue());
            data.addProperty("method", name);
            if (args.count() > 0) {
                var all = args.getAll();
                data.add("args", JsonValues.array(all, recorder.handles()));
                var bytes = dataLength(all.length > 0 ? all[0] : null);
                if (bytes >= 0) data.addProperty("bytes", bytes);
            }
            MethodResult result;
            try {
                result = inner.apply(target, context, args);
            } catch (LuaException e) {
                data.addProperty("ok", false);
                data.addProperty("error", e.getMessage());
                recorder.record("handle", data);
                throw e;
            }
            data.addProperty("ok", true);
            // websocket.receive yields; its result arrives as an event, so only immediate results are shown.
            if (result.getCallback() == null) {
                var values = result.getResult();
                data.add("result", JsonValues.array(values, recorder.handles()));
                var bytes = dataLength(values != null && values.length > 0 ? values[0] : null);
                if (bytes >= 0 && !data.has("bytes")) data.addProperty("bytes", bytes);
            }
            else data.addProperty("pending", true);
            recorder.record("handle", data);
            return result;
        }
    }

    /** Full length of a string-like value (the record keeps only the first characters), or -1. */
    private static long dataLength(Object value) {
        if (value instanceof java.nio.ByteBuffer b) return b.remaining();
        if (value instanceof byte[] b) return b.length;
        if (value instanceof String s) return s.length();
        return -1;
    }

    /** {@code peripheral_call} / {@code peripheral_return}. */
    private final class PeripheralCall implements LuaMethod {
        private final LuaMethod inner;

        PeripheralCall(LuaMethod inner) {
            this.inner = inner;
        }

        @Override
        public MethodResult apply(Object target, ILuaContext context, IArguments args) throws LuaException {
            if (!recorder.active()) return inner.apply(target, context, args);
            var id = recorder.nextCallId();
            var peripheral = args.count() > 0 ? String.valueOf(args.get(0)) : null;
            var method = args.count() > 1 ? String.valueOf(args.get(1)) : null;

            var call = new JsonObject();
            call.addProperty("id", id);
            call.addProperty("peripheral", peripheral);
            call.addProperty("method", method);
            call.add("args", JsonValues.array(args.count() > 2 ? args.drop(2).getAll() : new Object[0], recorder.handles()));
            recorder.record("peripheral_call", call);

            MethodResult result;
            try {
                result = inner.apply(target, context, args);
            } catch (LuaException e) {
                recordError(id, peripheral, method, e);
                throw e;
            }
            return finish(id, peripheral, method, result);
        }

        private MethodResult finish(long id, String peripheral, String method, MethodResult result) {
            var callback = result.getCallback();
            if (callback == null) {
                var data = base(id, peripheral, method);
                data.addProperty("ok", true);
                data.add("result", JsonValues.array(result.getResult(), recorder.handles()));
                recorder.record("peripheral_return", data);
                return result;
            }
            // Pending: the method yielded (main-thread task, waiting for an event). Follow the callback chain.
            ILuaCallback traced = event -> {
                try {
                    return finish(id, peripheral, method, callback.resume(event));
                } catch (LuaException e) {
                    recordError(id, peripheral, method, e);
                    throw e;
                }
            };
            var next = MethodResult.yield(result.getResult(), traced);
            return result.getErrorAdjust() == 0 ? next : next.adjustError(result.getErrorAdjust());
        }

        private void recordError(long id, String peripheral, String method, LuaException e) {
            var data = base(id, peripheral, method);
            data.addProperty("ok", false);
            data.addProperty("error", e.getMessage());
            recorder.record("peripheral_return", data);
        }

        private JsonObject base(long id, String peripheral, String method) {
            var data = new JsonObject();
            data.addProperty("id", id);
            data.addProperty("peripheral", peripheral);
            data.addProperty("method", method);
            return data;
        }
    }
}

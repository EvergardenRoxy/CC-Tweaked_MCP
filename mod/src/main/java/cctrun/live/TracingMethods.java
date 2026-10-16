// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.core.apis.PeripheralAPI;
import dan200.computercraft.core.methods.LuaMethod;
import dan200.computercraft.core.methods.MethodSupplier;

/**
 * The VM's method lookup with {@code peripheral.call} instrumented. Every peripheral access from Lua ends up in
 * {@link PeripheralAPI#call}: the ROM's {@code peripheral.wrap} and {@code peripheral.find} build their methods
 * from {@code peripheral.call}, which uses the native {@code call} (rom/apis/peripheral.lua).
 * <p>
 * Records {@code peripheral_call} (id, peripheral, method, args) and {@code peripheral_return} (id, peripheral,
 * method, ok, result or error). Main-thread methods (inventories, most generic peripherals) yield and finish on a
 * later tick; their return is recorded when the callback finishes.
 */
final class TracingMethods implements MethodSupplier<LuaMethod> {
    private final MethodSupplier<LuaMethod> inner;
    private final LiveMachine.Recorder recorder;

    TracingMethods(MethodSupplier<LuaMethod> inner, LiveMachine.Recorder recorder) {
        this.inner = inner;
        this.recorder = recorder;
    }

    @Override
    public boolean forEachSelfMethod(Object object, UntargetedConsumer<LuaMethod> consumer) {
        if (!(object instanceof PeripheralAPI)) return inner.forEachSelfMethod(object, consumer);
        return inner.forEachSelfMethod(object, (name, method, info) -> consumer.accept(name, wrap(name, method), info));
    }

    @Override
    public boolean forEachMethod(Object object, TargetedConsumer<LuaMethod> consumer) {
        if (!(object instanceof PeripheralAPI)) return inner.forEachMethod(object, consumer);
        return inner.forEachMethod(object, (target, name, method, info) -> consumer.accept(target, name, wrap(name, method), info));
    }

    private LuaMethod wrap(String name, LuaMethod method) {
        return "call".equals(name) ? new TracedCall(method) : method;
    }

    private final class TracedCall implements LuaMethod {
        private final LuaMethod inner;

        TracedCall(LuaMethod inner) {
            this.inner = inner;
        }

        @Override
        public MethodResult apply(Object target, dan200.computercraft.api.lua.ILuaContext context, IArguments args) throws LuaException {
            var id = recorder.nextCallId();
            var peripheral = args.count() > 0 ? String.valueOf(args.get(0)) : null;
            var method = args.count() > 1 ? String.valueOf(args.get(1)) : null;

            var call = new JsonObject();
            call.addProperty("id", id);
            call.addProperty("peripheral", peripheral);
            call.addProperty("method", method);
            call.add("args", JsonValues.array(args.count() > 2 ? args.drop(2).getAll() : new Object[0]));
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
                data.add("result", JsonValues.array(result.getResult()));
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

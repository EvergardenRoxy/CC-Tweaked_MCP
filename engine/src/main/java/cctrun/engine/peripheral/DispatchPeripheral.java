// SPDX-License-Identifier: MPL-2.0

package cctrun.engine.peripheral;

import cctrun.engine.JsonValues;
import cctrun.engine.Trace;
import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Base class for fake peripherals. Every method goes through {@link #invoke}, which records a
 * {@code peripheral_call} and a matching {@code peripheral_return} (result or error) in the trace.
 * <p>
 * Methods marked as main-thread run via {@link ILuaContext#executeMainThreadTask}, like CC: Tweaked's
 * generic peripherals (inventories, energy, fluids) in the real game: the call yields, runs on the next
 * server tick and resumes the program with a {@code task_complete} event.
 */
public abstract class DispatchPeripheral implements IDynamicPeripheral {
    /** Implementation of one method. May return {@code Object[]} or a {@link MethodResult}. */
    @FunctionalInterface
    protected interface Impl {
        Object call(IComputerAccess computer, ILuaContext context, IArguments args) throws LuaException;
    }

    private record Method(String name, boolean mainThread, boolean traced, Impl impl) {
    }

    private final String type;
    private final Set<String> additionalTypes;
    protected final Trace trace;
    private final List<Method> methods = new ArrayList<>();
    private final Map<String, Method> byName = new LinkedHashMap<>();

    protected DispatchPeripheral(String type, Set<String> additionalTypes, Trace trace) {
        this.type = type;
        this.additionalTypes = additionalTypes;
        this.trace = trace;
    }

    /** Register a method; extra names are aliases (e.g. setTextColour / setTextColor). */
    protected final void method(boolean mainThread, Impl impl, String... names) {
        for (var name : names) add(new Method(name, mainThread, true, impl));
    }

    /** Register a method whose calls are not traced (wired modem plumbing; see WiredModemPeripheral). */
    protected final void untracedMethod(Impl impl, String name) {
        add(new Method(name, false, false, impl));
    }

    private void add(Method method) {
        if (byName.putIfAbsent(method.name(), method) != null) throw new IllegalStateException("Duplicate method " + method.name());
        methods.add(method);
    }

    @Override
    public final String getType() {
        return type;
    }

    @Override
    public final Set<String> getAdditionalTypes() {
        return additionalTypes;
    }

    @Override
    public final String[] getMethodNames() {
        return methods.stream().map(Method::name).toArray(String[]::new);
    }

    final List<String> methodNames() {
        return methods.stream().map(Method::name).toList();
    }

    @Override
    public final MethodResult callMethod(IComputerAccess computer, ILuaContext context, int method, IArguments arguments) throws LuaException {
        return invoke(computer, context, methods.get(method), arguments);
    }

    /** Call by name; used by a wired modem's {@code callRemote}. */
    final MethodResult callByName(IComputerAccess computer, ILuaContext context, String name, IArguments arguments) throws LuaException {
        var method = byName.get(name);
        if (method == null) throw new LuaException("No such method " + name);
        return invoke(computer, context, method, arguments);
    }

    private MethodResult invoke(IComputerAccess computer, ILuaContext context, Method method, IArguments arguments) throws LuaException {
        if (!method.traced()) return toResult(method.impl().call(computer, context, arguments));

        var id = trace.nextCallId();
        var call = new JsonObject();
        call.addProperty("id", id);
        call.addProperty("peripheral", computer.getAttachmentName());
        call.addProperty("type", type);
        call.addProperty("method", method.name());
        call.add("args", JsonValues.array(arguments.getAll()));
        call.addProperty("main_thread", method.mainThread());
        trace.record("peripheral_call", call);

        if (method.mainThread()) {
            // Arguments are only valid during this call, so take a copy for the deferred task.
            var copy = new ObjectArguments(arguments.getAll());
            return context.executeMainThreadTask(() -> asArray(run(id, computer, context, method, copy)));
        }
        return toResult(run(id, computer, context, method, arguments));
    }

    private Object run(long id, IComputerAccess computer, ILuaContext context, Method method, IArguments arguments) throws LuaException {
        var data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("peripheral", computer.getAttachmentName());
        data.addProperty("method", method.name());
        try {
            var result = method.impl().call(computer, context, arguments);
            data.addProperty("ok", true);
            if (!(result instanceof MethodResult)) data.add("result", JsonValues.array(asArray(result)));
            trace.record("peripheral_return", data);
            return result;
        } catch (LuaException e) {
            data.addProperty("ok", false);
            data.addProperty("error", e.getMessage());
            trace.note("peripheral_return", data);
            throw e;
        } catch (RuntimeException e) {
            data.addProperty("ok", false);
            data.addProperty("error", "internal: " + e);
            trace.note("peripheral_return", data);
            throw e;
        }
    }

    private static Object[] asArray(Object result) {
        if (result == null) return new Object[0];
        if (result instanceof Object[] array) return array;
        throw new IllegalStateException("Main-thread methods must return Object[]");
    }

    private static MethodResult toResult(Object result) {
        if (result instanceof MethodResult r) return r;
        return MethodResult.of((Object[]) asArray(result));
    }

    /** The backing model (an inventory, a monitor, ...), as CC: Tweaked's own peripherals expose it. */
    @Override
    public abstract Object getTarget();

    @Override
    public final boolean equals(IPeripheral other) {
        return other == this;
    }

    // Small helpers for implementations.
    protected static Object[] values(Object... values) {
        return values;
    }
}

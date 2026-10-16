package cctrun.engine.peripheral;

import cctrun.engine.JsonValues;
import cctrun.engine.Trace;
import com.google.gson.JsonObject;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A wired modem (type {@code modem}, additional type {@code peripheral_hub}) connecting the computer to
 * named peripherals, e.g. {@code minecraft:chest_0}. CraftOS's {@code peripheral} API finds these through
 * the {@code *Remote} methods, so {@code peripheral.find}/{@code wrap} work on network names.
 * <p>
 * Only fake peripherals created by this tool can be on the network. Channel methods ({@code open},
 * {@code transmit}, ...) are recorded but no message is ever received.
 */
public final class WiredModemPeripheral extends DispatchPeripheral {
    private final String localName;
    private final Map<String, IPeripheral> network = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<Integer> openChannels = Collections.synchronizedSet(new TreeSet<>());

    public WiredModemPeripheral(String localName, Map<String, DispatchPeripheral> remote, Trace trace) {
        super("modem", Set.of("peripheral_hub"), trace);
        this.localName = localName;
        network.putAll(remote);

        method(false, (c, ctx, a) -> values(false), "isWireless");
        method(false, (c, ctx, a) -> values(localName), "getNameLocal");
        // CraftOS's peripheral API calls the lookup methods below many times per peripheral.find/wrap, so they
        // are not traced (they would bury the program's own calls). Calls made through callRemote are traced by
        // the target peripheral under its network name.
        untracedMethod((c, ctx, a) -> values(new ArrayList<>(network.keySet())), "getNamesRemote");
        untracedMethod((c, ctx, a) -> values(network.containsKey(a.getString(0))), "isPresentRemote");
        untracedMethod((c, ctx, a) -> {
            var p = network.get(a.getString(0));
            if (p == null) return null;
            var types = new ArrayList<Object>();
            types.add(p.getType());
            types.addAll(p.getAdditionalTypes());
            return types.toArray();
        }, "getTypeRemote");
        untracedMethod((c, ctx, a) -> {
            var p = network.get(a.getString(0));
            if (p == null) return null;
            var type = a.getString(1);
            return values(p.getType().equals(type) || p.getAdditionalTypes().contains(type));
        }, "hasTypeRemote");
        untracedMethod((c, ctx, a) -> {
            var p = network.get(a.getString(0));
            if (!(p instanceof DispatchPeripheral d)) return null;
            return values(d.methodNames());
        }, "getMethodsRemote");
        untracedMethod((c, ctx, a) -> {
            var remoteName = a.getString(0);
            var p = network.get(remoteName);
            if (!(p instanceof DispatchPeripheral d)) throw new LuaException("No peripheral: " + remoteName);
            var method = a.getString(1);
            return d.callByName(new RemoteAccess(c, remoteName, network), ctx, method, a.drop(2));
        }, "callRemote");

        method(false, (c, ctx, a) -> {
            openChannels.add(checkChannel(a.getInt(0)));
            return null;
        }, "open");
        method(false, (c, ctx, a) -> values(openChannels.contains(checkChannel(a.getInt(0)))), "isOpen");
        method(false, (c, ctx, a) -> {
            openChannels.remove(checkChannel(a.getInt(0)));
            return null;
        }, "close");
        method(false, (c, ctx, a) -> {
            openChannels.clear();
            return null;
        }, "closeAll");
        method(false, (c, ctx, a) -> {
            var data = new JsonObject();
            data.addProperty("modem", c.getAttachmentName());
            data.addProperty("channel", checkChannel(a.getInt(0)));
            data.addProperty("reply_channel", checkChannel(a.getInt(1)));
            data.add("payload", JsonValues.of(a.get(2)));
            trace.record("modem_transmit", data);
            return null;
        }, "transmit");
    }

    public Map<String, IPeripheral> network() {
        return network;
    }

    @Override
    public void attach(IComputerAccess computer) {
        // Real wired modems attach remote peripherals with an access whose name is the network name.
        for (var entry : network.entrySet()) entry.getValue().attach(new RemoteAccess(computer, entry.getKey(), network));
    }

    @Override
    public void detach(IComputerAccess computer) {
        for (var entry : network.entrySet()) entry.getValue().detach(new RemoteAccess(computer, entry.getKey(), network));
    }

    private static int checkChannel(int channel) throws LuaException {
        if (channel < 0 || channel > 65535) throw new LuaException("Expected number in range 0-65535");
        return channel;
    }

    @Override
    public Object getTarget() {
        return this;
    }
}

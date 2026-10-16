// SPDX-License-Identifier: MPL-2.0

package cctrun.engine.peripheral;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.WorkMonitor;

import java.util.Map;

/**
 * How a peripheral on a wired network sees the computer: its attachment name is its network name,
 * and other peripherals are looked up on the same network (as with a real wired modem).
 */
final class RemoteAccess implements IComputerAccess {
    private final IComputerAccess computer;
    private final String name;
    private final Map<String, IPeripheral> network;

    RemoteAccess(IComputerAccess computer, String name, Map<String, IPeripheral> network) {
        this.computer = computer;
        this.name = name;
        this.network = network;
    }

    @Override
    public String mount(String desiredLocation, Mount mount, String driveName) {
        return computer.mount(desiredLocation, mount, driveName);
    }

    @Override
    public String mountWritable(String desiredLocation, WritableMount mount, String driveName) {
        return computer.mountWritable(desiredLocation, mount, driveName);
    }

    @Override
    public void unmount(String location) {
        computer.unmount(location);
    }

    @Override
    public int getID() {
        return computer.getID();
    }

    @Override
    public void queueEvent(String event, Object... arguments) {
        computer.queueEvent(event, arguments);
    }

    @Override
    public String getAttachmentName() {
        return name;
    }

    @Override
    public Map<String, IPeripheral> getAvailablePeripherals() {
        return Map.copyOf(network);
    }

    @Override
    public IPeripheral getAvailablePeripheral(String name) {
        return network.get(name);
    }

    @Override
    public WorkMonitor getMainThreadMonitor() {
        return computer.getMainThreadMonitor();
    }
}

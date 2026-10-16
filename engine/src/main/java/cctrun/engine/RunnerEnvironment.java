package cctrun.engine;

import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.core.ComputerContext;
import dan200.computercraft.core.computer.ComputerEnvironment;
import dan200.computercraft.core.computer.GlobalEnvironment;
import dan200.computercraft.core.filesystem.JarMount;
import dan200.computercraft.core.metrics.MetricsObserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;

/** One throwaway computer: in-memory root drive, ROM and BIOS read from the CC: Tweaked core jar. */
final class RunnerEnvironment implements ComputerEnvironment, GlobalEnvironment {
    private final WritableMount root;
    private final File coreJar;

    RunnerEnvironment(WritableMount root) {
        this.root = root;
        try {
            coreJar = new File(ComputerContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the CC: Tweaked core jar", e);
        }
    }

    @Override
    public int getDay() {
        return 0;
    }

    @Override
    public double getTimeOfDay() {
        return 0;
    }

    @Override
    public MetricsObserver getMetrics() {
        return MetricsObserver.discard();
    }

    @Override
    public WritableMount createRootMount() {
        return root;
    }

    @Override
    public String getHostString() {
        return "ComputerCraft " + Versions.CC_TWEAKED + " (cct-run " + Versions.TOOL + ")";
    }

    @Override
    public String getUserAgent() {
        return "computercraft/" + Versions.CC_TWEAKED;
    }

    @Override
    public Mount createResourceMount(String domain, String subPath) {
        try {
            return new JarMount(coreJar, "data/" + domain + "/" + subPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream createResourceFile(String domain, String subPath) {
        return ComputerContext.class.getClassLoader().getResourceAsStream("data/" + domain + "/" + subPath);
    }
}

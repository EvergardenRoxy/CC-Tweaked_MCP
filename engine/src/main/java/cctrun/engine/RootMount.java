// SPDX-License-Identifier: MPL-2.0

package cctrun.engine;

import dan200.computercraft.api.filesystem.FileOperationException;
import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.core.filesystem.MemoryMount;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

/**
 * The computer's root drive: CC: Tweaked's {@link MemoryMount} with one difference. Its {@code rename} compares the
 * paths as strings, so {@code fs.move("/a.log", "/a.log.1")} fails with "Cannot move a directory inside itself"; the
 * game's {@code WritableFileMount} compares path segments ({@code java.nio.file.Path.startsWith}) and allows it.
 */
final class RootMount implements WritableMount {
    private final MemoryMount delegate;

    RootMount(MemoryMount delegate) {
        this.delegate = delegate;
    }

    @Override
    public void rename(String source, String dest) throws IOException {
        if (dest.startsWith(source) && !dest.equals(source) && !dest.startsWith(source + "/")) {
            // Same checks and order as WritableFileMount, then two renames MemoryMount accepts.
            if (!delegate.exists(source)) throw new FileOperationException(source, "No such file");
            if (delegate.exists(dest)) throw new FileOperationException(dest, "File exists");
            var temp = ".cctrun-rename";
            while (delegate.exists(temp) || temp.startsWith(source) || dest.startsWith(temp)) temp += "_";
            delegate.rename(source, temp);
            try {
                delegate.rename(temp, dest);
            } catch (IOException e) {
                delegate.rename(temp, source);
                throw e;
            }
            return;
        }
        delegate.rename(source, dest);
    }

    @Override
    public boolean exists(String path) throws IOException {
        return delegate.exists(path);
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        return delegate.isDirectory(path);
    }

    @Override
    public void list(String path, List<String> contents) throws IOException {
        delegate.list(path, contents);
    }

    @Override
    public long getSize(String path) throws IOException {
        return delegate.getSize(path);
    }

    @Override
    public SeekableByteChannel openForRead(String path) throws IOException {
        return delegate.openForRead(path);
    }

    @Override
    public BasicFileAttributes getAttributes(String path) throws IOException {
        return delegate.getAttributes(path);
    }

    @Override
    public void makeDirectory(String path) throws IOException {
        delegate.makeDirectory(path);
    }

    @Override
    public void delete(String path) throws IOException {
        delegate.delete(path);
    }

    @Override
    @SuppressWarnings("removal")
    public SeekableByteChannel openForWrite(String path) throws IOException {
        return delegate.openForWrite(path);
    }

    @Override
    @SuppressWarnings("removal")
    public SeekableByteChannel openForAppend(String path) throws IOException {
        return delegate.openForAppend(path);
    }

    @Override
    public SeekableByteChannel openFile(String path, Set<OpenOption> options) throws IOException {
        return delegate.openFile(path, options);
    }

    @Override
    public long getRemainingSpace() throws IOException {
        return delegate.getRemainingSpace();
    }

    @Override
    public long getCapacity() {
        return delegate.getCapacity();
    }

    @Override
    public boolean isReadOnly(String path) throws IOException {
        return delegate.isReadOnly(path);
    }
}

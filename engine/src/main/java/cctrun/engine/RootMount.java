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
    private final java.util.Set<QuotaChannel> channels = new java.util.HashSet<>();
    private long writtenBytes;
    private static final long MIN_ENTRY = 500; // WritableFileMount minimum allocation

    RootMount(MemoryMount delegate) throws IOException {
        this.delegate = delegate;
        requireSpace(0);
    }

    private long used() throws IOException {
        long used = 0;
        var pending = new java.util.ArrayDeque<String>();
        pending.add("");
        while (!pending.isEmpty()) {
            var path = pending.removeFirst();
            used += Math.max(MIN_ENTRY, delegate.getSize(path));
            if (delegate.isDirectory(path)) {
                var children = new java.util.ArrayList<String>();
                delegate.list(path, children);
                for (var child : children) pending.add(path.isEmpty() ? child : path + "/" + child);
            }
        }
        return used;
    }

    private void requireSpace(long growth) throws IOException {
        if (growth > delegate.getCapacity() - used()) throw new IOException("Out of space");
    }

    synchronized long writtenBytes() { return writtenBytes; }

    @Override
    public synchronized void rename(String source, String dest) throws IOException {
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
            renamed(source, dest);
            return;
        }
        delegate.rename(source, dest);
        renamed(source, dest);
    }

    private void renamed(String source, String dest) {
        for (var c : channels) if (c.path.equals(source) || c.path.startsWith(source + "/")) c.path = dest + c.path.substring(source.length());
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
        return openFile(path, dan200.computercraft.api.filesystem.MountConstants.READ_OPTIONS);
    }

    @Override
    public BasicFileAttributes getAttributes(String path) throws IOException {
        return delegate.getAttributes(path);
    }

    @Override
    public synchronized void makeDirectory(String path) throws IOException {
        long missing = 0;
        var prefix = "";
        for (var part : path.split("/")) {
            prefix = prefix.isEmpty() ? part : prefix + "/" + part;
            if (!prefix.isEmpty() && !delegate.exists(prefix)) missing++;
        }
        requireSpace(missing * MIN_ENTRY);
        delegate.makeDirectory(path);
    }

    @Override
    public synchronized void delete(String path) throws IOException {
        delegate.delete(path);
        // A removed MemoryMount entry can remain reachable through an open channel. Release it too, so repeated
        // delete/open/write cannot keep unaccounted byte arrays alive outside the quota.
        for (var c : java.util.List.copyOf(channels)) if (c.path.equals(path) || c.path.startsWith(path + "/")) c.close();
    }

    @Override
    @SuppressWarnings("removal")
    public SeekableByteChannel openForWrite(String path) throws IOException {
        return openFile(path, dan200.computercraft.api.filesystem.MountConstants.WRITE_OPTIONS);
    }

    @Override
    @SuppressWarnings("removal")
    public SeekableByteChannel openForAppend(String path) throws IOException {
        return openFile(path, dan200.computercraft.api.filesystem.MountConstants.APPEND_OPTIONS);
    }

    @Override
    public synchronized SeekableByteChannel openFile(String path, Set<OpenOption> options) throws IOException {
        boolean writable = options.contains(java.nio.file.StandardOpenOption.WRITE) || options.contains(java.nio.file.StandardOpenOption.APPEND);
        if (!delegate.exists(path) && options.contains(java.nio.file.StandardOpenOption.CREATE)) requireSpace(MIN_ENTRY);
        var channel = new QuotaChannel(path, delegate.openFile(path, options), writable);
        channels.add(channel);
        return channel;
    }

    @Override
    public synchronized long getRemainingSpace() throws IOException {
        return Math.max(0, getCapacity() - used());
    }

    @Override
    public long getCapacity() {
        return delegate.getCapacity();
    }

    @Override
    public boolean isReadOnly(String path) throws IOException {
        return delegate.isReadOnly(path);
    }

    private final class QuotaChannel implements SeekableByteChannel {
        private String path;
        private SeekableByteChannel inner;
        private final boolean writable;
        QuotaChannel(String path, SeekableByteChannel inner, boolean writable) { this.path = path; this.inner = inner; this.writable = writable; }
        private SeekableByteChannel open() throws java.nio.channels.ClosedChannelException {
            if (inner == null) throw new java.nio.channels.ClosedChannelException();
            return inner;
        }
        public int read(java.nio.ByteBuffer dst) throws IOException { synchronized (RootMount.this) { return open().read(dst); } }
        public int write(java.nio.ByteBuffer src) throws IOException {
            synchronized (RootMount.this) {
                var channel = open();
                if (!writable) throw new java.nio.channels.NonWritableChannelException();
                long position = channel.position();
                if (position > getCapacity() - src.remaining()) throw new IOException("Out of space");
                long growth = Math.max(MIN_ENTRY, Math.max(channel.size(), position + src.remaining())) - Math.max(MIN_ENTRY, channel.size());
                requireSpace(growth);
                int count = channel.write(src);
                writtenBytes += count;
                return count;
            }
        }
        public long position() throws IOException { synchronized (RootMount.this) { return open().position(); } }
        public SeekableByteChannel position(long p) throws IOException { synchronized (RootMount.this) { open().position(p); return this; } }
        public long size() throws IOException { synchronized (RootMount.this) { return open().size(); } }
        public SeekableByteChannel truncate(long size) throws IOException { synchronized (RootMount.this) { open().truncate(size); return this; } }
        public boolean isOpen() { synchronized (RootMount.this) { return inner != null && inner.isOpen(); } }
        public void close() throws IOException {
            synchronized (RootMount.this) {
                if (inner == null) return;
                try { inner.close(); } finally { inner = null; channels.remove(this); }
            }
        }
    }
}

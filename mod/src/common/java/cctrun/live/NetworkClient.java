// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One physical client connection. Loader adapters marshal sends onto the client thread. */
final class NetworkClient implements AutoCloseable {
    private final UUID epoch = UUID.randomUUID();
    private final Consumer<String> sender;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean closed;
    private boolean ready;
    private String unavailable = "network handshake pending";
    private NetworkBridge bridge;

    private record Pending(NetworkProtocol.Assembly assembly, CompletableFuture<NetworkProtocol.Reply> future) {}

    NetworkClient(Consumer<String> sender) { this.sender = sender; }

    void start(Path home) throws java.io.IOException {
        bridge = new NetworkBridge(this, home);
        bridge.start();
        var arguments = new JsonObject();
        arguments.addProperty("version", NetworkProtocol.VERSION);
        send("hello", arguments).thenAccept(response -> {
            var reply = NetworkProtocol.negotiate(response);
            synchronized (this) {
                if (closed) return;
                ready = reply.status() == 200;
                unavailable = ready ? "" : reply.data().get("error").getAsString();
            }
        });
    }

    synchronized CompletableFuture<NetworkProtocol.Reply> call(String operation, JsonObject arguments) {
        if (!ready || closed) return CompletableFuture.completedFuture(NetworkProtocol.Reply.error(503, unavailable));
        return send(operation, arguments);
    }

    private synchronized CompletableFuture<NetworkProtocol.Reply> send(String operation, JsonObject arguments) {
        if (closed) return CompletableFuture.completedFuture(NetworkProtocol.Reply.error(503, "Minecraft disconnected"));
        if (pending.size() >= NetworkProtocol.MAX_PENDING)
            return CompletableFuture.completedFuture(NetworkProtocol.Reply.error(429, "too many pending game requests"));
        var id = UUID.randomUUID();
        var message = NetworkProtocol.request(id, epoch, operation, arguments).toString();
        if (message.length() > NetworkProtocol.MAX_REQUEST_CHARS)
            return CompletableFuture.completedFuture(NetworkProtocol.Reply.error(413, "request too large"));
        var future = new CompletableFuture<NetworkProtocol.Reply>();
        pending.put(id, new Pending(new NetworkProtocol.Assembly(), future));
        future.completeOnTimeout(NetworkProtocol.Reply.error(504, "Minecraft request timed out"), 10, TimeUnit.SECONDS)
            .whenComplete((reply, error) -> { synchronized (this) { pending.remove(id); } });
        try { sender.accept(message); }
        catch (RuntimeException e) { future.complete(NetworkProtocol.Reply.error(503, "Minecraft connection unavailable")); }
        return future;
    }

    synchronized void receive(NetworkProtocol.Frame frame) {
        var entry = pending.get(frame.requestId());
        if (closed || entry == null) return;
        try {
            var reply = entry.assembly().accept(frame, epoch);
            if (reply != null) entry.future().complete(reply);
        } catch (RuntimeException e) {
            entry.future().complete(NetworkProtocol.Reply.error(502, "invalid game response"));
        }
    }

    @Override public void close() {
        NetworkBridge toClose;
        synchronized (this) {
            closed = true;
            ready = false;
            unavailable = "Minecraft disconnected";
            for (var entry : java.util.List.copyOf(pending.values()))
                entry.future().complete(NetworkProtocol.Reply.error(503, unavailable));
            pending.clear();
            toClose = bridge;
        }
        if (toClose != null) toClose.close();
    }
}

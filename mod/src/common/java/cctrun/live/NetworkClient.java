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
    private boolean handshaking;
    private boolean incompatible;
    private int attempts;
    private String unavailable = "network handshake pending";
    private NetworkBridge bridge;

    private record Pending(NetworkProtocol.Assembly assembly, CompletableFuture<NetworkProtocol.Reply> future) {}

    NetworkClient(Consumer<String> sender) { this.sender = sender; }

    void start(Path home) throws java.io.IOException {
        bridge = new NetworkBridge(this, home);
        bridge.start();
        handshake();
    }

    /**
     * Sends hello unless one is in flight. A slow login, a lost reply or a temporarily disabled bridge is retried
     * with backoff for the life of this connection; only an incompatible protocol (426) stops retrying.
     */
    private synchronized void handshake() {
        if (closed || ready || incompatible || handshaking) return;
        handshaking = true;
        var arguments = new JsonObject();
        arguments.addProperty("version", NetworkProtocol.VERSION);
        send("hello", arguments).thenAccept(this::handshaken);
    }

    private synchronized void handshaken(NetworkProtocol.Reply response) {
        handshaking = false;
        if (closed) return;
        var reply = NetworkProtocol.negotiate(response);
        ready = reply.status() == 200;
        if (ready) { unavailable = ""; attempts = 0; return; }
        var error = reply.data().get("error");
        unavailable = error != null && error.isJsonPrimitive() ? error.getAsString() : "network handshake failed";
        if (reply.status() == 426) { incompatible = true; return; }
        var delay = retryDelaySeconds(attempts++);
        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS).execute(this::handshake);
    }

    /** 1, 2, 4 ... seconds, capped at 30. */
    static long retryDelaySeconds(int attempt) { return Math.min(30, 1L << Math.min(attempt, 5)); }

    synchronized boolean ready() { return ready && !closed; }

    synchronized CompletableFuture<NetworkProtocol.Reply> call(String operation, JsonObject arguments) {
        if (!ready || closed) {
            handshake(); // No-op while one is in flight, after close, or for an incompatible server.
            return CompletableFuture.completedFuture(NetworkProtocol.Reply.error(503, unavailable));
        }
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

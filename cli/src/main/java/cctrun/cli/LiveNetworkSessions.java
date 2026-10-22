// SPDX-License-Identifier: MPL-2.0
package cctrun.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One instance per stdio MCP connection. The game client never heartbeats on behalf of a dead MCP process. */
final class LiveNetworkSessions implements AutoCloseable {
    interface Transport { String send(LiveTools.Endpoint endpoint, HttpRequest.Builder request, Duration timeout) throws McpTools.ToolError; }
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private final Transport transport;
    private final UUID openKey = UUID.randomUUID();
    private final Map<LiveTools.Endpoint, Agent> agents = new LinkedHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(8,r -> {
        var thread = new Thread(r, "cct-live-mcp-heartbeat"); thread.setDaemon(true); return thread;
    });
    private boolean closed;
    private static final class Agent {
        final String id;
        ScheduledFuture<?> pulse;
        final Map<Integer, JsonObject> leases = new ConcurrentHashMap<>();
        Agent(String id) { this.id = UUID.fromString(id).toString(); }
    }
    LiveNetworkSessions(Transport transport) { this.transport = transport; }

    private synchronized Agent agent(LiveTools.Endpoint endpoint) throws McpTools.ToolError {
        if (closed) throw new McpTools.ToolError("MCP session is closed");
        var agent = agents.get(endpoint);
        if (agent != null) return agent;
        if (agents.size() >= 8) throw new McpTools.ToolError("too many active game bridges for this MCP connection");
        var request = HttpRequest.newBuilder(endpoint.base().resolve("network/sessions/open"))
            .header("X-CCT-Open-Key", openKey.toString()).POST(HttpRequest.BodyPublishers.noBody());
        try {
            var reply = JsonParser.parseString(transport.send(endpoint, request, TIMEOUT)).getAsJsonObject();
            agent = new Agent(reply.get("agent_session_id").getAsString());
        } catch (RuntimeException e) { throw new McpTools.ToolError("invalid network session response; update both cct-live and cct-run"); }
        agents.put(endpoint, agent);
        var registered = agent;
        agent.pulse = heartbeat.scheduleWithFixedDelay(() -> pulse(endpoint, registered), 15, 15, TimeUnit.SECONDS);
        return agent;
    }

    void decorate(LiveTools.Endpoint endpoint, HttpRequest.Builder request) throws McpTools.ToolError {
        var agent = agent(endpoint);
        request.header("X-CCT-Session", agent.id);
        var parts = request.build().uri().getPath().split("/");
        if (parts.length >= 4 && parts[1].equals("computers")) {
            var lease = agent.leases.get(Integer.parseInt(parts[2]));
            if (lease != null) request.header("X-CCT-Lease", lease.get("lease_id").getAsString())
                .header("X-CCT-Lease-Epoch", lease.get("lease_epoch").getAsString());
        }
    }

    String control(LiveTools.Endpoint endpoint, int computer, String op) throws McpTools.ToolError {
        var agent = agent(endpoint);
        synchronized (agent) {
            var path = "computers/" + computer + "/control" + (op.equals("status") ? "" : "/" + op);
            var request = HttpRequest.newBuilder(endpoint.base().resolve(path));
            if (op.equals("status")) request.GET(); else request.POST(HttpRequest.BodyPublishers.noBody());
            decorate(endpoint, request);
            var result = transport.send(endpoint, request, TIMEOUT);
            if (op.equals("release")) agent.leases.remove(computer);
            else {
                try {
                    var data = JsonParser.parseString(result).getAsJsonObject();
                    if (data.get("owned").getAsBoolean()) {
                        UUID.fromString(data.get("lease_id").getAsString());
                        data.get("lease_epoch").getAsLong();
                        agent.leases.put(computer, data);
                    } else agent.leases.remove(computer);
                } catch (RuntimeException e) { throw new McpTools.ToolError("invalid control lease response"); }
            }
            return result;
        }
    }

    void heartbeat() {
        Map<LiveTools.Endpoint, Agent> snapshot;
        synchronized (this) { if (closed) return; snapshot = new LinkedHashMap<>(agents); }
        for (var entry : snapshot.entrySet()) pulse(entry.getKey(), entry.getValue());
    }

    private void pulse(LiveTools.Endpoint endpoint, Agent agent) {
        synchronized (this) { if (closed || agents.get(endpoint) != agent) return; }
        try { lifecycle(endpoint, agent, "heartbeat", TIMEOUT); }
        catch (McpTools.ToolError e) {
            synchronized (this) { agents.remove(endpoint, agent); }
            if (agent.pulse != null) agent.pulse.cancel(false);
            agent.leases.clear();
        }
    }

    private void lifecycle(LiveTools.Endpoint endpoint, Agent agent, String op, Duration timeout) throws McpTools.ToolError {
        transport.send(endpoint, HttpRequest.newBuilder(endpoint.base().resolve("network/sessions/" + op))
            .header("X-CCT-Session", agent.id).POST(HttpRequest.BodyPublishers.noBody()), timeout);
    }

    @Override public void close() {
        Map<LiveTools.Endpoint, Agent> snapshot;
        synchronized (this) { closed = true; snapshot = new LinkedHashMap<>(agents); agents.clear(); }
        heartbeat.shutdownNow();
        for (var entry : snapshot.entrySet()) {
            try { lifecycle(entry.getKey(), entry.getValue(), "close", Duration.ofSeconds(2)); }
            catch (McpTools.ToolError ignored) { /* Server-side expiry handles interrupted or crashed transports. */ }
        }
    }
}

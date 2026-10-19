// SPDX-License-Identifier: MPL-2.0
package cctrun.live;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Request policy shared by the production endpoint and socket-level tests. */
final class HttpPolicy {
    private HttpPolicy() {}
    static boolean hostAllowed(String host, int port) {
        return host != null && (host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port) || host.equals("[::1]:" + port));
    }
    static boolean authorised(String header, byte[] token) {
        return header != null && header.startsWith("Bearer ")
            && MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }
    static final class TooLarge extends IOException {}
    static final class ReadTimeout extends IOException {}

    /** Close before response headers exist: JDK HttpServer closes the connection and unblocks a slow body read.
     * No global sun.net.httpserver properties are changed. */
    static byte[] read(HttpExchange exchange, int limit, long timeoutMillis, ScheduledExecutorService deadlines) throws IOException {
        var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        var timer = deadlines.schedule(() -> {
            timedOut.set(true);
            exchange.close();
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            boolean oversized = false;
            var declared = exchange.getRequestHeaders().getFirst("Content-Length");
            if (declared != null) {
                try { oversized = Long.parseLong(declared) > limit; }
                catch (NumberFormatException ignored) { }
            }
            var saved = new java.io.ByteArrayOutputStream(Math.min(limit, 8192));
            var buffer = new byte[8192];
            int count;
            // Drain an oversized request within the same deadline before returning 413. This also prevents
            // HttpExchange.close() from blocking on an unread slow tail after response headers have been sent.
            while ((count = exchange.getRequestBody().read(buffer)) != -1) {
                if (timedOut.get()) throw new ReadTimeout();
                if (count > limit - saved.size()) oversized = true;
                if (!oversized) saved.write(buffer, 0, count);
            }
            if (timedOut.get()) throw new ReadTimeout();
            if (oversized) throw new TooLarge();
            return saved.toByteArray();
        } catch (IOException e) {
            if (timedOut.get()) throw new ReadTimeout();
            throw e;
        } finally { timer.cancel(false); }
    }
}

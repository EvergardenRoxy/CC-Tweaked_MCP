// SPDX-License-Identifier: MPL-2.0

package cctrun.live;

import com.google.gson.JsonObject;
import dan200.computercraft.core.apis.handles.AbstractHandle;
import dan200.computercraft.core.apis.http.request.HttpResponseHandle;
import dan200.computercraft.core.apis.http.websocket.WebsocketHandle;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Numbers the handle objects one VM sees (open files, HTTP responses, websockets) so trace records can refer to
 * them: in JSON a handle is {@code {"handle": n, "type": ..., "path"/"url": ...}} instead of a Java object name.
 * Weak keys: a handle Lua drops is forgotten with it. Handles do not override equals, so keys compare by identity.
 */
final class Handles {
    private record Info(long id, String type, String path, String mode, String url) {
    }

    private final Map<Object, Info> known = new WeakHashMap<>();
    private long next;

    static boolean isHandle(Object value) {
        return value instanceof AbstractHandle || value instanceof HttpResponseHandle || value instanceof WebsocketHandle;
    }

    /** ROM files (read-only, loaded by the shell and require on every program start) are not traced. */
    static boolean isRomPath(Object path) {
        if (!(path instanceof String p)) return false;
        var trimmed = p.replace('\\', '/');
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        return trimmed.equals("rom") || trimmed.startsWith("rom/");
    }

    synchronized boolean isRomFile(Object handle) {
        var info = known.get(handle);
        return info != null && isRomPath(info.path);
    }

    synchronized void file(Object handle, String path, String mode) {
        // ROM handles are never recorded, so they do not use up handle numbers.
        known.put(handle, new Info(isRomPath(path) ? 0 : ++next, "file", path, mode, null));
    }

    synchronized void http(Object handle, String url, String type) {
        if (!known.containsKey(handle)) known.put(handle, new Info(++next, type, null, null, url));
    }

    /** The JSON for a handle, or null if {@code value} is not one. Unknown handles are numbered on first sight. */
    synchronized JsonObject describe(Object value) {
        if (!isHandle(value)) return null;
        var info = known.get(value);
        if (info == null) {
            var type = value instanceof HttpResponseHandle ? "http_response" : value instanceof WebsocketHandle ? "websocket" : "file";
            info = new Info(++next, type, null, null, null);
            known.put(value, info);
        }
        var out = new JsonObject();
        out.addProperty("handle", info.id);
        out.addProperty("type", info.type);
        if (info.path != null) out.addProperty("path", info.path);
        if (info.mode != null) out.addProperty("mode", info.mode);
        if (info.url != null) out.addProperty("url", info.url);
        return out;
    }
}

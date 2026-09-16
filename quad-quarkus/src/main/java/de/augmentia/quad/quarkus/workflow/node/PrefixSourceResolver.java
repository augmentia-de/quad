package de.augmentia.quad.quarkus.workflow.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.WorkspaceResolver;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Resolves a node's {@code prefixSource} into concrete prefix text for the
 * agent-scoped prefix chain (§6). Sources, resolved at runtime:
 * <ul>
 *   <li><b>inline JSON element</b> — a bare string (used as-is) or any JSON tree
 *       element (serialized to text);</li>
 *   <li><b>URL</b> — {@code {"url": "https://..."}} or a bare
 *       {@code http(s)://} string, fetched at runtime (size-capped);</li>
 *   <li><b>file</b> — {@code {"file": "path"}} or a bare {@code file://} string;
 *       absolute paths are read as-is, relative paths are resolved against the
 *       session workspace (size-capped);</li>
 *   <li><b>step reference</b> — {@code {"step": "<nodeId>"}} or {@code {"self": true}}
 *       resolves to the referenced node's output (requires it to have executed,
 *       DAG order; otherwise the source resolves to null = no prefix).</li>
 * </ul>
 */
public final class PrefixSourceResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SOURCE_CHARS = 256 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PrefixSourceResolver() {
    }

    /**
     * Resolves the raw {@code prefixSource} config value into prefix text, or
     * {@code null}/blank when nothing usable can be produced (unresolvable step,
     * fetch/read error, absent config).
     */
    @SuppressWarnings("unchecked")
    public static String resolve(Object raw, WorkflowExecutionContext ctx, String nodeId) {
        if (raw == null) return null;
        if (raw instanceof String s) return resolveString(s, ctx);
        if (raw instanceof Map<?, ?> m) return resolveMap((Map<String, Object>) m, ctx, nodeId);
        return serialize(raw);
    }

    private static String resolveString(String s, WorkflowExecutionContext ctx) {
        String t = s.trim();
        if (t.startsWith("https://") || t.startsWith("http://")) {
            return fetchUrl(t);
        }
        if (t.startsWith("file://")) {
            return readFile(t.substring("file://".length()), ctx);
        }
        return s;
    }

    private static String resolveMap(Map<String, Object> m, WorkflowExecutionContext ctx, String nodeId) {
        if (m.containsKey("url")) {
            return fetchUrl(String.valueOf(m.get("url")));
        }
        if (m.containsKey("file")) {
            return readFile(String.valueOf(m.get("file")), ctx);
        }
        if (m.containsKey("step")) {
            return ctx.output(String.valueOf(m.get("step")));
        }
        if (Boolean.TRUE.equals(m.get("self"))) {
            return ctx.output(nodeId);
        }
        return serialize(m);
    }

    private static String serialize(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String fetchUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            return cap(new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readFile(String path, WorkflowExecutionContext ctx) {
        try {
            Path target;
            if (path.startsWith("/")) {
                target = Path.of(path);
            } else {
                String sessionId = ctx.sessionState() != null ? ctx.sessionState().getSessionId() : "default";
                target = Path.of(WorkspaceResolver.resolve(sessionId, path));
            }
            return cap(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException | java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private static String cap(String s) {
        if (s == null) return null;
        return s.length() <= MAX_SOURCE_CHARS ? s : s.substring(0, MAX_SOURCE_CHARS);
    }
}
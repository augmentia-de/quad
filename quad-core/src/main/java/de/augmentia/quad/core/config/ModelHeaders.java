package de.augmentia.quad.core.config;

import de.augmentia.quad.core.session.CurrentSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional, environment-driven HTTP headers for LLM providers.
 *
 * <p>Provider-neutral and <b>off by default</b>: when no environment variable is set and no
 * session is active, the returned map is empty and no headers are added — other providers are
 * unaffected.</p>
 *
 * <p>Configured via environment variables / system properties (see {@link ConfigReader} for
 * precedence: {@code vault.<KEY>} → env → system property):</p>
 * <ul>
 *   <li>{@code QUAD_LLM_USER_AGENT} → {@code User-Agent}</li>
 *   <li>{@code QUAD_LLM_SESSION_ID} → <b>fallback</b> value of the header named by
 *       {@code QUAD_LLM_SESSION_HEADER} (default {@code x-opencode-session}); overridden by the
 *       active session's id (see {@link #current()})</li>
 *   <li>{@code QUAD_LLM_HEADERS} → additional headers as comma-separated {@code Name=Value}</li>
 * </ul>
 *
 * <p><b>Per session:</b> {@link #current()} resolves the session header from the currently active
 * {@link CurrentSession} (the {@code AgentSessionState} bound during an agent run), so the session
 * id is <b>variable per session/run</b> — not process-scoped. {@code QUAD_LLM_SESSION_ID} is only
 * used when no session is active.</p>
 *
 * <p>For OpenCode Go set {@code QUAD_LLM_USER_AGENT=my-test-agent/1.0}; the session header is taken
 * from the active session automatically (or from {@code QUAD_LLM_SESSION_ID} as fallback).</p>
 */
public final class ModelHeaders {

    public static final String USER_AGENT = "User-Agent";
    public static final String DEFAULT_SESSION_HEADER = "x-opencode-session";

    private ModelHeaders() {
    }

    /** @return custom headers from the environment, or an empty map if none are configured. */
    public static Map<String, String> fromEnv() {
        Map<String, String> headers = new LinkedHashMap<>();

        String userAgent = ConfigReader.get("QUAD_LLM_USER_AGENT");
        if (notBlank(userAgent)) {
            headers.put(USER_AGENT, userAgent);
        }

        String sessionId = ConfigReader.get("QUAD_LLM_SESSION_ID");
        if (notBlank(sessionId)) {
            headers.put(sessionHeaderName(), sessionId);
        }

        String extra = ConfigReader.get("QUAD_LLM_HEADERS");
        if (notBlank(extra)) {
            for (String pair : extra.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (!name.isEmpty()) {
                        headers.put(name, value);
                    }
                }
            }
        }
        return headers;
    }

    /**
     * Resolved headers for the <b>current request</b>: the static config from {@link #fromEnv()}
     * plus — when a session is active ({@link CurrentSession#getCurrent()}) — the live session id as
     * the session header. This makes {@code QUAD_LLM_SESSION_ID} variable per session/run instead of
     * process-scoped.
     *
     * <p>Intended to be passed as a {@code Supplier} to LangChain4j's
     * {@code customHeaders(Supplier<Map<String,String>>)}, so it is evaluated per request.</p>
     */
    public static Map<String, String> current() {
        Map<String, String> headers = fromEnv();
        String sessionId = currentSessionId();
        if (notBlank(sessionId)) {
            headers.put(sessionHeaderName(), sessionId);
        }
        return headers;
    }

    /** The configured session header name, defaulting to {@link #DEFAULT_SESSION_HEADER}. */
    public static String sessionHeaderName() {
        String headerName = ConfigReader.get("QUAD_LLM_SESSION_HEADER");
        return notBlank(headerName) ? headerName : DEFAULT_SESSION_HEADER;
    }

    /** Session id of the currently active {@link CurrentSession}, or {@code null} if none. */
    private static String currentSessionId() {
        try {
            var state = CurrentSession.getCurrent();
            return state != null ? state.getSessionId() : null;
        } catch (Throwable t) {
            // Never let session lookup break an LLM call.
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

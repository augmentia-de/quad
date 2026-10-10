package de.augmentia.quad.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional, environment-driven HTTP headers for LLM providers.
 *
 * <p>Provider-neutral and <b>off by default</b>: when no environment variable is set, the
 * returned map is empty and no headers are added — other providers are unaffected.</p>
 *
 * <p>Configured via environment variables / system properties:</p>
 * <ul>
 *   <li>{@code QUAD_LLM_USER_AGENT} → {@code User-Agent}</li>
 *   <li>{@code QUAD_LLM_SESSION_ID} → value of the header named by
 *       {@code QUAD_LLM_SESSION_HEADER} (default {@code x-opencode-session})</li>
 *   <li>{@code QUAD_LLM_HEADERS} → additional headers as comma-separated {@code Name=Value}</li>
 * </ul>
 *
 * <p>For OpenCode Go set {@code QUAD_LLM_USER_AGENT=my-test-agent/1.0} and
 * {@code QUAD_LLM_SESSION_ID=ses_...}.</p>
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
            String headerName = ConfigReader.get("QUAD_LLM_SESSION_HEADER");
            headers.put(notBlank(headerName) ? headerName : DEFAULT_SESSION_HEADER, sessionId);
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

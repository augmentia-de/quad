package de.augmentia.quad.core.security;

/**
 * Configuration for RFC 8693 Token Exchange per MCP server.
 * Parsed from mcp-config.json or system properties.
 */
public record TokenExchangeConfig(
    String issuer,
    String clientId,
    String clientSecret,
    String audience,
    String[] scopes,
    long tokenLifetimeSeconds,
    String subjectTokenType,
    String requestedTokenType
) {

    public static TokenExchangeConfig fromEnv() {
        return new TokenExchangeConfig(
            getEnv("quad.security.token-exchange.issuer", ""),
            getEnv("quad.security.token-exchange.client-id", "quad-backend"),
            getEnv("quad.security.token-exchange.credentials.secret", ""),
            getEnv("quad.security.token-exchange.default-audience", "mcp-server"),
            parseScopes(getEnv("quad.security.token-exchange.default-scopes", "openid,profile")),
            parseLong(getEnv("quad.security.token-exchange.token-lifetime-seconds", "300")),
            getEnv("quad.security.token-exchange.subject-token-type",
                "urn:ietf:params:oauth:token-type:access_token"),
            getEnv("quad.security.token-exchange.requested-token-type",
                "urn:ietf:params:oauth:token-type:access_token")
        );
    }

    public static TokenExchangeConfig forServer(String serverName, String defaultAudience) {
        String prefix = "quad.security.token-exchange.servers." + serverName + ".";
        String audience = getEnv(prefix + "audience", defaultAudience);
        String scopesStr = getEnv(prefix + "scopes", "");

        return new TokenExchangeConfig(
            getEnv("quad.security.token-exchange.issuer", ""),
            getEnv("quad.security.token-exchange.client-id", "quad-backend"),
            getEnv("quad.security.token-exchange.credentials.secret", ""),
            audience,
            scopesStr.isBlank() ? new String[0] : parseScopes(scopesStr),
            parseLong(getEnv("quad.security.token-exchange.token-lifetime-seconds", "300")),
            getEnv("quad.security.token-exchange.subject-token-type",
                "urn:ietf:params:oauth:token-type:access_token"),
            getEnv("quad.security.token-exchange.requested-token-type",
                "urn:ietf:params:oauth:token-type:access_token")
        );
    }

    private static String getEnv(String key, String fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(key.replace('.', '_').replace('-', '_').toUpperCase());
        if (val != null && !val.isBlank()) return val;
        return fallback;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 300; }
    }

    private static String[] parseScopes(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        return csv.split(",");
    }
}

package de.augmentia.quad.core.security;

import java.util.Map;
import java.util.Set;

/**
 * Framework-agnostic security configuration read from quad.security.* properties.
 * Used by ToolGuard and other security components in quad-core.
 */
public record SecurityConfig(
    boolean oidcEnabled,
    boolean identityEnabled,
    boolean tokenExchangeEnabled,
    boolean toolsEnabled,
    boolean toolsDefaultDeny,
    Map<String, Set<String>> toolAllowedRoles,
    Map<String, Set<String>> mcpToolAllowedRoles,
    boolean auditEnabled,
    boolean auditLogIdentity,
    boolean auditLogArguments,
    boolean auditLogResult,
    String auditCorrelationHeader
) {

    public static SecurityConfig disabled() {
        return new SecurityConfig(
            false, false, false, false, false,
            Map.of(), Map.of(),
            true, true, true, false, "X-Correlation-ID"
        );
    }

    public static SecurityConfig fromEnv() {
        boolean oidc = parseBoolean(getEnv("quad.security.oidc.enabled", "false"));
        boolean identity = parseBoolean(getEnv("quad.security.identity.enabled", "false"));
        boolean tokenExchange = parseBoolean(getEnv("quad.security.token-exchange.enabled", "false"));
        boolean tools = parseBoolean(getEnv("quad.security.tools.enabled", "false"));
        boolean defaultDeny = parseBoolean(getEnv("quad.security.tools.default-deny", "false"));
        boolean audit = parseBoolean(getEnv("quad.security.audit.enabled", "true"));
        boolean auditIdentity = parseBoolean(getEnv("quad.security.audit.log-identity", "true"));
        boolean auditArgs = parseBoolean(getEnv("quad.security.audit.log-arguments", "true"));
        boolean auditResult = parseBoolean(getEnv("quad.security.audit.log-result", "false"));
        String correlation = getEnv("quad.security.audit.correlation-header", "X-Correlation-ID");

        Map<String, Set<String>> toolRoles = parseRoleMap(
            getEnv("quad.security.tools.allowed-roles", ""));
        Map<String, Set<String>> mcpRoles = parseRoleMap(
            getEnv("quad.security.tools.allowed-mcp-roles", ""));

        return new SecurityConfig(
            oidc, identity, tokenExchange, tools, defaultDeny,
            toolRoles, mcpRoles,
            audit, auditIdentity, auditArgs, auditResult, correlation
        );
    }

    private static String getEnv(String key, String fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(key.replace('.', '_').replace('-', '_').toUpperCase());
        if (val != null && !val.isBlank()) return val;
        return fallback;
    }

    private static boolean parseBoolean(String s) {
        return Boolean.parseBoolean(s);
    }

    private static Map<String, Set<String>> parseRoleMap(String csv) {
        if (csv == null || csv.isBlank()) return Map.of();
        var result = new java.util.HashMap<String, Set<String>>();
        for (String entry : csv.split(",")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                String tool = parts[0].trim();
                String roles = parts[1].trim();
                if ("*".equals(roles)) {
                    result.put(tool, Set.of("*"));
                } else {
                    Set<String> roleSet = new java.util.HashSet<>();
                    for (String r : roles.split("\\|")) {
                        String trimmed = r.trim();
                        if (!trimmed.isEmpty()) roleSet.add(trimmed);
                    }
                    result.put(tool, Set.copyOf(roleSet));
                }
            }
        }
        return Map.copyOf(result);
    }
}

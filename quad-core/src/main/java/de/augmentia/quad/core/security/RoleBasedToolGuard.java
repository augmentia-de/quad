package de.augmentia.quad.core.security;

import java.util.Map;
import java.util.Set;

/**
 * Role-based tool guard. Each tool maps to a set of allowed roles.
 * A user needs at least one of the tool's required roles to execute it.
 * <p>
 * Tools not in the map are controlled by {@code defaultAllow}.
 * Tools mapped to {@code Set.of("*")} are always allowed.
 */
public class RoleBasedToolGuard implements ToolGuard {

    private final Map<String, Set<String>> toolAllowedRoles;
    private final Map<String, Set<String>> mcpToolAllowedRoles;
    private final boolean defaultAllow;

    public RoleBasedToolGuard(Map<String, Set<String>> toolAllowedRoles,
                              Map<String, Set<String>> mcpToolAllowedRoles,
                              boolean defaultAllow) {
        this.toolAllowedRoles = Map.copyOf(toolAllowedRoles);
        this.mcpToolAllowedRoles = Map.copyOf(mcpToolAllowedRoles);
        this.defaultAllow = defaultAllow;
    }

    public static RoleBasedToolGuard fromConfig(SecurityConfig config) {
        return new RoleBasedToolGuard(
            config.toolAllowedRoles(),
            config.mcpToolAllowedRoles(),
            !config.toolsDefaultDeny()
        );
    }

    @Override
    public boolean isAllowed(String toolName, SecurityContext ctx) {
        if (ctx == null) return defaultAllow;

        Set<String> required = resolveRoles(toolName);
        if (required == null) return defaultAllow;
        if (required.contains("*")) return true;

        return ctx.roles().stream().anyMatch(required::contains);
    }

    private Set<String> resolveRoles(String toolName) {
        if (toolName != null && toolName.startsWith("mcp_")) {
            String mcpKey = toolName.substring(4);
            Set<String> roles = mcpToolAllowedRoles.get(mcpKey);
            if (roles != null) return roles;
        }
        return toolAllowedRoles.get(toolName);
    }
}

package de.augmentia.quad.core.security;

/**
 * Guards tool execution based on security context.
 * Implementations check whether a user is allowed to invoke a specific tool.
 */
public interface ToolGuard {

    /**
     * Checks if the given security context is allowed to execute the named tool.
     *
     * @param toolName the tool to check (e.g. "executeBash", "mcp_filesystem_read")
     * @param ctx the current security context (user identity + roles)
     * @return true if execution is allowed
     */
    boolean isAllowed(String toolName, SecurityContext ctx);

    /** Guard that allows everything (disabled state). */
    ToolGuard ALLOW_ALL = (toolName, ctx) -> true;

    /** Guard that denies everything. */
    ToolGuard DENY_ALL = (toolName, ctx) -> false;
}

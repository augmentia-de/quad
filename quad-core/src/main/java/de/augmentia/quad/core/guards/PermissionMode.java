package de.augmentia.quad.core.guards;

/**
 * Tool-authorization mode adopted from openworker's permission model.
 * AUTO = every (non-blocked) command allowed, INTERACTIVE = conservative allow
 * set, PLAN = read-only (no command execution).
 */
public enum PermissionMode {
    PLAN,
    INTERACTIVE,
    AUTO
}

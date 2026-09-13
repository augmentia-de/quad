package de.augmentia.quad.core.guards;

import java.util.Set;

/**
 * Command / write authorization engine (Befehlsebene), ported from openworker.
 * Path-level access is deliberately NOT part of this engine — {@code WorkspaceResolver}
 * (session confinement + {@code GrantedDirectory}/{@code Access}) already covers it.
 */
public class PermissionEngine {

    private static final Set<String> BLOCKED_COMMANDS = Set.of("rm -rf /", "mkfs", "dd if=");

    /** Whether a write to a path is allowed under the given mode. */
    public boolean canWrite(PermissionMode mode, String path) {
        return switch (mode) {
            case AUTO -> true;
            case INTERACTIVE -> !isSensitivePath(path);
            case PLAN -> false;
        };
    }

    /** Whether a shell command may be executed under the given mode. */
    public boolean canExecuteCommand(PermissionMode mode, String cmd) {
        if (cmd != null && BLOCKED_COMMANDS.stream().anyMatch(cmd::contains)) {
            return false;
        }
        return switch (mode) {
            case AUTO -> true;
            case INTERACTIVE -> cmd != null && !cmd.startsWith("sudo") && !cmd.contains("chmod 777");
            case PLAN -> false;
        };
    }

    private boolean isSensitivePath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.contains("/etc/passwd") || lower.contains("/etc/shadow")
            || lower.contains(".ssh") || lower.contains(".gnupg")
            || lower.contains("/proc/") || lower.contains("/sys/");
    }
}

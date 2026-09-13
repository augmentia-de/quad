package de.augmentia.quad.core.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;

@ApplicationScoped
public class WorkspaceResolver {

    /** Defaults to the process CWD ('.') so agents work in the current directory. */
    public static final String WORKSPACE_BASE = Path.of(System.getProperty("quad.workspace",
        System.getenv().getOrDefault("QUAD_WORKSPACE", "."))).toAbsolutePath().normalize().toString();

    @Inject
    CurrentSession currentSession;

    @ConfigProperty(name = "quad.workspace", defaultValue = ".")
    Path baseWorkspace;

    public static String resolve(String sessionId) {
        return WORKSPACE_BASE + "/" + sessionId;
    }

    public static String resolve(String sessionId, String subPath) {
        return resolve(sessionId) + "/" + subPath;
    }

    public Path sessionDir() {
        AgentSessionState current = currentSession != null ? currentSession.get() : CurrentSession.getCurrent();
        String sessionId = current != null ? current.getSessionId() : "default";
        return baseWorkspace().resolve(sessionId);
    }

    public Path sessionDir(String sessionId) {
        return baseWorkspace().resolve(sessionId);
    }

    public Path sessionDir(String sessionId, String subPath) {
        return sessionDir(sessionId).resolve(subPath);
    }

    /**
     * The workspace root (= the configured {@code quad.workspace} base). Tools use this as the
     * default directory for path-less calls (e.g. {@code findFiles(pattern)},
     * {@code listDirectory()}) so they operate on the workspace instead of the per-session subdir.
     */
    public Path workspaceDir() {
        return baseWorkspace();
    }

    /**
     * Resolves the current workflow directory (CWD) for the given session state.
     * <p>
     * The result is always confined to the session root: {@code base/{sessionId}}.
     * A {@code currentCwd()} of {@code "."} resolves to the session root itself;
     * {@code pushCwd("backend")} resolves to {@code base/{sessionId}/backend}.
     * Paths that escape the session root (e.g. via {@code ".."}) are rejected
     * with a {@link SecurityException}.
     *
     * @param state the session state carrying the CWD stack
     * @return the absolute, normalized CWD path within the session root
     */
    public Path cwdDir(AgentSessionState state) {
        Path sessionRoot = sessionDir(state.getSessionId()).normalize();
        String current = state.currentCwd() != null ? state.currentCwd() : ".";
        Path cwd = sessionRoot.resolve(current).normalize();
        if (!cwd.startsWith(sessionRoot)) {
            throw new SecurityException(
                "Access denied: CWD escapes session workspace: " + current);
        }
        return cwd;
    }

    /**
     * Resolves a sub-path against the current workflow directory.
     * Guards against path traversal outside the session root.
     *
     * @param state   the session state carrying the CWD stack
     * @param subPath the path relative to the current CWD
     * @return the absolute, normalized path within the session root
     */
    public Path cwdDir(AgentSessionState state, String subPath) {
        Path sessionRoot = sessionDir(state.getSessionId()).normalize();
        Path target = cwdDir(state).resolve(subPath).normalize();
        if (!target.startsWith(sessionRoot)) {
            throw new SecurityException(
                "Access denied: Path resides outside of session workspace: " + subPath);
        }
        return target;
    }

    /**
     * Resolves a path for read access, allowing both the session workspace
     * (with CWD) and any user-granted directories.
     * <p>
     * Only grant directories with at least {@link Access#READ} are permitted.
     * The session root itself is always readable.
     *
     * @param state   the session state carrying CWD and granted directories
     * @param subPath the path (absolute or relative to current CWD)
     * @return the absolute, normalized path that is safe to access
     * @throws SecurityException if the path is outside the workspace and all granted dirs
     */
    public Path resolveForRead(AgentSessionState state, String subPath) {
        Path sessionRoot = sessionDir(state.getSessionId()).normalize();
        Path target = resolveAgainstCwd(state, subPath);

        if (target.startsWith(sessionRoot)) {
            return symlinkSafe(target, sessionRoot);
        }
        if (state.accessFor(target) != null) {
            Access access = state.accessFor(target);
            return symlinkSafeAgainstGrant(target, access, state);
        }
        throw new SecurityException(
            "Access denied: '" + subPath + "' is outside the session workspace and all granted directories.");
    }

    /**
     * Resolves a path for write access. Only the session workspace (always
     * writable) and granted directories with {@link Access#READ_WRITE} are
     * permitted. Read-only granted dirs are rejected for writes.
     *
     * @throws SecurityException if the path is not writable
     */
    public Path resolveForWrite(AgentSessionState state, String subPath) {
        Path sessionRoot = sessionDir(state.getSessionId()).normalize();
        Path target = resolveAgainstCwd(state, subPath);

        if (target.startsWith(sessionRoot)) {
            return symlinkSafe(target, sessionRoot);
        }
        Access access = state.accessFor(target);
        if (access == Access.READ_WRITE) {
            return symlinkSafeAgainstGrant(target, access, state);
        }
        throw new SecurityException(
            "Access denied: '" + subPath + "' is not writable (either outside the workspace or only read-only granted).");
    }

    private Path resolveAgainstCwd(AgentSessionState state, String subPath) {
        return cwdDir(state).resolve(subPath).normalize();
    }

    /**
     * Real-path containment check against the session root to prevent
     * symlink escapes. Falls back to lexical check when the path does not exist.
     */
    private Path symlinkSafe(Path target, Path root) {
        Path realTarget = real(target);
        Path realRoot = root.toAbsolutePath().normalize();
        if (realTarget.startsWith(realRoot)) {
            return target;
        }
        if (target.startsWith(realRoot)) {
            return target;
        }
        throw new SecurityException(
            "Access denied: real path of '" + target + "' escapes the session workspace (symlink).");
    }

    private Path symlinkSafeAgainstGrant(Path target, Access access, AgentSessionState state) {
        Path realTarget = real(target);
        for (GrantedDirectory gd : state.grantedDirectories()) {
            if (realTarget.startsWith(real(gd.path()))) {
                return target;
            }
        }
        // lexical fallback
        for (GrantedDirectory gd : state.grantedDirectories()) {
            if (target.startsWith(gd.path())) {
                return target;
            }
        }
        throw new SecurityException(
            "Access denied: real path of '" + target + "' does not reside in any granted directory (symlink).");
    }

    private static Path real(Path p) {
        try {
            Path abs = p.toAbsolutePath().normalize();
            Path rp = abs.toRealPath();
            return rp == null ? abs : rp;
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private Path baseWorkspace() {
        Path base = baseWorkspace != null ? baseWorkspace : Path.of(WORKSPACE_BASE);
        return base.toAbsolutePath().normalize();
    }
}
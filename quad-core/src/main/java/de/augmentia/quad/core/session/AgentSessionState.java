package de.augmentia.quad.core.session;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;

import de.augmentia.quad.core.security.SecurityContext;
import de.augmentia.quad.core.session.saga.CompensatingAction;
import de.augmentia.quad.core.session.saga.CompensatingAction.SagaStep;
import de.augmentia.quad.core.session.memory.SessionMemory;

public class AgentSessionState {
    private String sessionId = UUID.randomUUID().toString();
    private String tenantId = "default";
    private final List<String> findings = new ArrayList<>();
    private String currentProject = "Project Alpha";
    private final java.util.List<StateMutationListener> mutationListeners = new ArrayList<>();
    private final List<StateMutationEvent> pendingMutations = new ArrayList<>();
    private final Deque<SagaStep> sagaLog = new java.util.ArrayDeque<>();
    private boolean sagaFailed = false;
    private final AtomicInteger lockCounter = new AtomicInteger(0);
    private Set<String> lastToolNames = Set.of();
    private final CwdManager cwd = new CwdManager();
    private SessionMemory memory = new SessionMemory();
    private SecurityContext securityContext;
    private final Set<GrantedDirectory> grantedDirs = ConcurrentHashMap.newKeySet();

    public static AgentSessionState create(String sessionId) {
        AgentSessionState state = new AgentSessionState();
        state.sessionId = sessionId;
        return state;
    }

    public String getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public List<String> findings() { return List.copyOf(findings); }
    public String getCurrentProject() { return currentProject; }
    public void setCurrentProject(String project) { this.currentProject = project; }
    public Set<String> getLastToolNames() { return lastToolNames; }
    public void setLastToolNames(Set<String> names) { this.lastToolNames = Set.copyOf(names); }

    /** Sitzungs-Memory (Konversations-Historie + Kompression) */
    public SessionMemory memory() { return memory; }

    public void setMemory(SessionMemory memory) {
        if (memory != null) this.memory = memory;
    }

    /** Security context for the current user (identity + roles). */
    public SecurityContext getSecurityContext() { return securityContext; }
    public void setSecurityContext(SecurityContext securityContext) { this.securityContext = securityContext; }

    // ── Current Workflow Directory (CWD) ──

    public CwdManager getCwd() { return cwd; }

    public void pushCwd(String cwd) { this.cwd.push(cwd); }

    public void popCwd() { this.cwd.pop(); }

    public String currentCwd() { return cwd.current(); }

    public void setCwd(String cwd) { this.cwd.reset().push(cwd); }

    public void resetCwd() { this.cwd.reset(); }

    public String cwdRoot() { return cwd.root(); }

    public int cwdDepth() { return cwd.depth(); }

    // ── User-granted directories (expand sandbox beyond session root) ──

    /**
     * Registers a user-granted directory. Defaults to read-only access;
     * use {@link #grantDir(Path, boolean)} to grant write access.
     *
     * @return the registered {@link GrantedDirectory}
     */
    public GrantedDirectory grantDir(Path path) {
        return grantDir(path, false);
    }

    /**
     * Registers a user-granted directory with explicit write permission.
     *
     * @param path      absolute or relative directory to allow
     * @param writable  when {@code true}, also allow write operations
     * @return the registered {@link GrantedDirectory}
     */
    public GrantedDirectory grantDir(Path path, boolean writable) {
        GrantedDirectory gd = new GrantedDirectory(path,
                writable ? Access.READ_WRITE : Access.READ);
        grantedDirs.add(gd);
        return gd;
    }

    /** Registers a directory with an explicit access level. */
    public GrantedDirectory grantDir(Path path, Access access) {
        GrantedDirectory gd = new GrantedDirectory(path, access);
        grantedDirs.add(gd);
        return gd;
    }

    /** Removes a previously granted directory. */
    public boolean revokeDir(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return grantedDirs.removeIf(gd -> gd.path().equals(normalized));
    }

    /** All currently granted directories (immutable copy, normalized). */
    public Set<GrantedDirectory> grantedDirectories() {
        return Set.copyOf(grantedDirs);
    }

    /** Whether the given absolute path falls under any granted directory. */
    public boolean isGranted(Path absolutePath) {
        Path p = absolutePath.toAbsolutePath().normalize();
        for (GrantedDirectory gd : grantedDirs) {
            if (p.startsWith(gd.path())) return true;
        }
        return false;
    }

    /** Access level for a path within a granted directory, or {@code null} if not granted. */
    public Access accessFor(Path absolutePath) {
        Path p = absolutePath.toAbsolutePath().normalize();
        return grantedDirs.stream()
                .filter(gd -> p.startsWith(gd.path()))
                .map(gd -> gd.isWritable() ? Access.READ_WRITE : Access.READ)
                .reduce((a, b) -> a == Access.READ_WRITE || b == Access.READ_WRITE
                        ? Access.READ_WRITE : Access.READ)
                .orElse(null);
    }

    // ── Mutation listeners ──

    public void attach(StateMutationListener l) { mutationListeners.add(l); }

    public <T> T set(String field, T value) {
        T old = getFieldValue(field);
        pendingMutations.add(new StateMutationEvent(sessionId, field, old, value));
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T> T getFieldValue(String field) {
        return switch (field) {
            case "findings" -> (T) new ArrayList<>(findings);
            case "currentProject" -> (T) currentProject;
            default -> null;
        };
    }

    public void addFinding(String finding) {
        findings.add(finding);
        pendingMutations.add(new StateMutationEvent(sessionId, "findings", null, finding));
    }

    public void dispatchMutationEvents() {
        if (pendingMutations.isEmpty()) return;
        List<StateMutationEvent> events = List.copyOf(pendingMutations);
        pendingMutations.clear();
        for (StateMutationEvent e : events) {
            for (StateMutationListener l : mutationListeners) {
                l.onStateChanged(e.sessionId(), e.field(), e.oldValue(), e.newValue());
            }
        }
    }

    public void registerCompensation(String toolName, CompensatingAction action) {
        sagaLog.push(new SagaStep(toolName, UUID.randomUUID().toString(), action));
    }

    public Deque<SagaStep> getSagaLog() { return sagaLog; }
    public void markSagaFailed() { this.sagaFailed = true; }
    public boolean isSagaFailed() { return sagaFailed; }
    public int acquireLock() { return lockCounter.incrementAndGet(); }
    public void releaseLock(int token) { if (lockCounter.get() == token) lockCounter.decrementAndGet(); }

    public record StateMutationEvent(String sessionId, String field, Object oldValue, Object newValue) {}
}
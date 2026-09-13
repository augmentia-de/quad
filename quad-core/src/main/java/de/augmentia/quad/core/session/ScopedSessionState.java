package de.augmentia.quad.core.session;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.augmentia.quad.core.session.saga.CompensatingAction;
import de.augmentia.quad.core.session.saga.CompensatingAction.SagaStep;
import de.augmentia.quad.core.tool.DynamicSubAgentTool;

/**
 * Selective state sharing between parent and sub-agents.
 * <p>
 * A {@code ScopedSessionState} is an isolated view of a parent
 * {@link AgentSessionState}. It only shares fields that are explicitly exposed:
 * <ul>
 *   <li>{@code sessionId} — inherited from parent (read-only)</li>
 *   <li>{@code tenantId} — inherited from parent (read-only)</li>
 *   <li>{@code currentProject} — inherited from parent (read-only)</li>
 *   <li>{@code findings} — combined parent findings + own findings</li>
 * </ul>
 * Isolated (own instance per sub-agent):
 * <ul>
 *   <li>{@code cwd} — own {@link CwdManager}</li>
 *   <li>{@code lastToolNames} — own set</li>
 * </ul>
 * Not shared:
 * <ul>
 *   <li>{@code sagaLog}, {@code sagaFailed} — compensation log is agent-local</li>
 *   <li>{@code mutationListeners}, {@code pendingMutations} — event system is agent-local</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 * AgentSessionState parent = new AgentSessionState();
 * parent.addFinding("Step 1 completed");
 *
 * AgentSessionState child = ScopedSessionState.childOf(parent);
 * child.addFinding("Step 2 in progress");
 *
 * parent.findings()  // → ["Step 1 completed"]
 * child.findings()   // → ["Step 1 completed", "Step 2 in progress"]
 * </pre>
 *
 * @see DynamicSubAgentTool
 */
public class ScopedSessionState extends AgentSessionState {

    private final AgentSessionState parent;
    private final List<String> ownFindings = new ArrayList<>();
    private final CwdManager ownCwd = new CwdManager();
    private final AtomicInteger ownLockCounter = new AtomicInteger(0);
    private Set<String> ownLastToolNames = Set.of();

    /**
     * Creates a child state that selectively shares fields from the parent.
     *
     * @param parent the parent state
     * @return a new, isolated ScopedSessionState
     */
    public static ScopedSessionState childOf(AgentSessionState parent) {
        if (parent == null) throw new IllegalArgumentException("parent must not be null");
        return new ScopedSessionState(parent);
    }

    private ScopedSessionState(AgentSessionState parent) {
        this.parent = parent;
    }

    // ── Inherited fields (from parent, read-only) ──

    @Override
    public String getSessionId() {
        return parent.getSessionId();
    }

    @Override
    public String getTenantId() {
        return parent.getTenantId();
    }

    @Override
    public String getCurrentProject() {
        return parent.getCurrentProject();
    }

    // ── Findings: parent + own ──

    @Override
    public List<String> findings() {
        List<String> combined = new ArrayList<>(parent.findings());
        combined.addAll(ownFindings);
        return List.copyOf(combined);
    }

    @Override
    public void addFinding(String finding) {
        ownFindings.add(finding);
    }

    /**
     * Returns only the own findings of this scope (without parent findings).
     */
    public List<String> ownFindings() {
        return List.copyOf(ownFindings);
    }

    // ── Isolated fields (own instance) ──

    @Override
    public CwdManager getCwd() {
        return ownCwd;
    }

    @Override
    public void pushCwd(String cwd) {
        ownCwd.push(cwd);
    }

    @Override
    public void popCwd() {
        ownCwd.pop();
    }

    @Override
    public String currentCwd() {
        return ownCwd.current();
    }

    @Override
    public void setCwd(String cwd) {
        ownCwd.reset().push(cwd);
    }

    @Override
    public void resetCwd() {
        ownCwd.reset();
    }

    @Override
    public String cwdRoot() {
        return ownCwd.root();
    }

    @Override
    public int cwdDepth() {
        return ownCwd.depth();
    }

    @Override
    public Set<String> getLastToolNames() {
        return ownLastToolNames;
    }

    @Override
    public void setLastToolNames(Set<String> names) {
        this.ownLastToolNames = Set.copyOf(names);
    }

    @Override
    public int acquireLock() {
        return ownLockCounter.incrementAndGet();
    }

    @Override
    public void releaseLock(int token) {
        if (ownLockCounter.get() == token) {
            ownLockCounter.decrementAndGet();
        }
    }

    // ── Not shared: empty instances ──

    @Override
    public Deque<SagaStep> getSagaLog() {
        return new java.util.ArrayDeque<>();
    }

    @Override
    public void registerCompensation(String toolName, CompensatingAction action) {
        // Saga compensation is agent-local — no forwarding to parent
    }

    @Override
    public void markSagaFailed() {
        // Isolated
    }

    @Override
    public boolean isSagaFailed() {
        return false;
    }

    @Override
    public void attach(StateMutationListener l) {
        // Mutation events are agent-local
    }

    @Override
    public <T> T set(String field, T value) {
        return value;
    }

    @Override
    public void dispatchMutationEvents() {
        // No events to dispatch
    }

    // ── Parent access ──

    /**
     * Returns the parent state (for debugging/introspection only).
     */
    public AgentSessionState parent() {
        return parent;
    }
}

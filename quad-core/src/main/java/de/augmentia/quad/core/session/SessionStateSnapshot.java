package de.augmentia.quad.core.session;

import de.augmentia.quad.core.session.memory.SessionMemory;
import de.augmentia.quad.core.session.saga.CompensatingAction;

import java.util.*;

/**
 * Fully serializable snapshot of {@link AgentSessionState}.
 * Contains all fields that can reconstruct a session state after a restart
 * (CWD stack, saga log metadata, findings, memory summary).
 *
 * Saga compensating actions (lambdas) are NOT serializable —
 * they are restored as an empty list on restore (compensation
 * only runs within a single run, not across restarts).
 */
public record SessionStateSnapshot(
    String sessionId,
    String tenantId,
    List<String> findings,
    String currentProject,
    List<String> cwdStack,
    String cwdRoot,
    List<SagaStepSnapshot> sagaLog,
    boolean sagaFailed,
    Set<String> lastToolNames,
    List<GrantedDirSnapshot> grantedDirs,
    MemorySnapshot memory
) {

    public record SagaStepSnapshot(String toolName, String executionId) {}

    public record GrantedDirSnapshot(String path, Access access) {}

    public record MemorySnapshot(String summary, int maxMessages) {}

    public static SessionStateSnapshot from(AgentSessionState state) {
        var cwdStack = state.getCwd().stackCopy();

        var sagaSteps = new ArrayList<SagaStepSnapshot>();
        for (var step : state.getSagaLog()) {
            sagaSteps.add(new SagaStepSnapshot(step.toolName(), step.executionId()));
        }

        var granted = new ArrayList<GrantedDirSnapshot>();
        for (var gd : state.grantedDirectories()) {
            granted.add(new GrantedDirSnapshot(gd.path().toString(), gd.access()));
        }

        MemorySnapshot mem = null;
        if (state.memory() != null) {
            mem = new MemorySnapshot(state.memory().summary(), state.memory().maxMessages());
        }

        return new SessionStateSnapshot(
            state.getSessionId(),
            state.getTenantId(),
            List.copyOf(state.findings()),
            state.getCurrentProject(),
            List.copyOf(cwdStack),
            state.cwdRoot(),
            List.copyOf(sagaSteps),
            state.isSagaFailed(),
            state.getLastToolNames() != null ? Set.copyOf(state.getLastToolNames()) : Set.of(),
            List.copyOf(granted),
            mem
        );
    }

    public AgentSessionState toState() {
        AgentSessionState state = AgentSessionState.create(sessionId);
        state.setTenantId(tenantId);
        if (findings != null) {
            for (var f : findings) state.addFinding(f);
        }
        if (currentProject != null) state.setCurrentProject(currentProject);

        // Reconstruct CWD stack
        if (cwdStack != null && !cwdStack.isEmpty()) {
            // Last element = root; setCwd sets root and pushes it
            state.setCwd(cwdStack.get(cwdStack.size() - 1));
            // Push remaining elements from innermost to outermost (second-to-last → first)
            for (int i = cwdStack.size() - 2; i >= 0; i--) {
                state.pushCwd(cwdStack.get(i));
            }
        }

        if (sagaLog != null) {
            for (var step : sagaLog) {
                state.getSagaLog().push(new CompensatingAction.SagaStep(
                    step.toolName(), step.executionId(), s -> {}));
            }
        }
        if (sagaFailed) state.markSagaFailed();
        if (lastToolNames != null) state.setLastToolNames(lastToolNames);
        if (grantedDirs != null) {
            for (var gd : grantedDirs) {
                if (gd == null || gd.path() == null) continue;
                state.grantDir(java.nio.file.Path.of(gd.path()),
                        gd.access() != null ? gd.access() : Access.READ);
            }
        }
        if (memory != null) {
            var mem = new SessionMemory(memory.maxMessages());
            state.setMemory(mem);
        }
        return state;
    }
}
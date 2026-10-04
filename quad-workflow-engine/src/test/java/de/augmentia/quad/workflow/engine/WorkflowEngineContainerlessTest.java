package de.augmentia.quad.workflow.engine;

import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.core.workflow.run.WorkflowNodeResult;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.workflow.engine.context.InputBuilder;
import de.augmentia.quad.workflow.engine.internal.RunStateWriter;
import de.augmentia.quad.workflow.engine.internal.WorkflowExecutionContext;
import de.augmentia.quad.workflow.engine.internal.WorkflowSupport;
import de.augmentia.quad.workflow.engine.node.NodeExecutor;
import de.augmentia.quad.workflow.engine.node.executor.AgentNodeExecutor;
import de.augmentia.quad.workflow.engine.node.NodeExecutorRegistry;
import de.augmentia.quad.workflow.engine.node.NodeRunner;
import de.augmentia.quad.workflow.engine.port.AgentInvocationPort;
import de.augmentia.quad.workflow.engine.port.RunEventPort;
import de.augmentia.quad.workflow.engine.port.StepCheckpointPort;
import de.augmentia.quad.workflow.engine.port.WorkflowDefinitionPort;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the engine runs without a DI container.
 *
 * <p>This is the actual reason for the whole refactoring: the
 * Spring Boot worker in {@code quad-workflow-worker} has no Arc, and exactly
 * this class should be usable there nonetheless. This test builds the engine
 * with {@code new} and executes a run — as soon as an {@code @Inject}
 * comes into play, the container is missing and the construction breaks immediately.
 *
 * <p>The ports are trivial stubs here. That is deliberate: what is checked is not
 * the adapter side, but that the engine has no knowledge of the container.
 */
class WorkflowEngineContainerlessTest {

    @Test
    void runsAWorkflowWithoutAnyContainer() {
        var runs = new InMemoryRunPort();
        var checkpoints = new InMemoryCheckpoints();
        var events = new RecordingEvents();
        var writer = new RunStateWriter(runs, checkpoints);
        var support = new WorkflowSupport(runs, writer);
        var scheduler = new WorkflowScheduler(support, writer);
        var definitions = new StaticDefinitions();
        var executor = new CountingExecutor();
        var agentExecutor = new AgentNodeExecutor(
                new StubAgents(), new InputBuilder(definitions), support, writer, events);

        var engine = new WorkflowEngine(
                definitions,
                support,
                scheduler,
                new NodeExecutorRegistry(List.of(agentExecutor, executor)),
                agentExecutor,
                writer,
                runs,
                events,
                checkpoints);

        ApiDtos.WorkflowDef wf = workflowWithOneNode("n1");
        WorkflowRunState run = runs.create(wf.id, List.of("n1"), "");

        engine.execute(wf, run);

        assertThat(run.status).isEqualTo("completed");
        // "start" is handled by the AgentNodeExecutor, not by the registry executor:
        // exactly this fallback rule is checked here as well.
        assertThat(executor.executions).isZero();
        // the run was persisted via the port, not directly into a table
        assertThat(runs.saved).isTrue();
        // the node really wrote a result
        assertThat(run.nodeResults).anyMatch(r -> "completed".equals(r.status())
                && "stub-antwort".equals(r.output()));
        // lifecycle events went via the port
        assertThat(events.phases).contains("RUN_STARTED", "RUN_COMPLETED");
        assertThat(events.runCounter).isEqualTo(1);
    }

    @Test
    void resumesFromACheckpoint() {
        var runs = new InMemoryRunPort();
        var checkpoints = new InMemoryCheckpoints();
        var writer = new RunStateWriter(runs, checkpoints);
        var support = new WorkflowSupport(runs, writer);
        var executor = new CountingExecutor();
        var definitions = new StaticDefinitions();
        var events = new RecordingEvents();
        var agentExecutor = new AgentNodeExecutor(
                new StubAgents(), new InputBuilder(definitions), support, writer, events);

        var engine = new WorkflowEngine(
                definitions,
                support,
                new WorkflowScheduler(support, writer),
                new NodeExecutorRegistry(List.of(agentExecutor, executor)),
                agentExecutor, writer, runs, events, checkpoints);

        ApiDtos.WorkflowDef wf = workflowWithOneNode("n1");
        WorkflowRunState run = runs.create(wf.id, List.of("n1"), "");
        checkpoints.saved.put(run.runId, new StepCheckpointPort.Checkpoint(
                run.runId, 1, "completed", "{}", null));

        engine.execute(wf, run, true);

        assertThat(run.status).isEqualTo("completed");
        // the restore went via the port, not via a direct DB access
        assertThat(checkpoints.loads).isEqualTo(1);
    }

    @Test
    void registryResolvesKnownTypesAndFallsBackForAgentPaths() {
        var loop = new StubExecutor(Set.of("loop"));
        var agent = new StubExecutor(Set.of("agent"));
        var registry = new NodeExecutorRegistry(List.of(loop, agent));

        assertThat(registry.forType("loop")).isSameAs(loop);
        assertThat(registry.forType("agent")).isSameAs(agent);
        // special cases go via the agent executor
        assertThat(registry.forType("messaging-in")).isSameAs(agent);
        assertThat(registry.forType("messaging-out")).isSameAs(agent);
        assertThat(registry.forType("gibt-es-nicht")).isNull();
        assertThat(registry.forType(null)).isNull();
        assertThat(registry.registeredTypes()).contains("loop", "agent");
    }

    @Test
    void runStateRestartResetsResultsButKeepsTheRun() {
        var run = WorkflowRunState.start("run-1", "wf-1");
        run.nodeResults.add(new WorkflowNodeResult("n1", "agent", "Eins", "completed", "ok", "in"));
        run.stepIndex = 7;

        run.restart();

        assertThat(run.restartCount).isEqualTo(1);
        assertThat(run.runId).isEqualTo("run-1");
        assertThat(run.status).isEqualTo("running");
        assertThat(run.stepIndex).isZero();
        assertThat(run.nodeResults).hasSize(1);
        assertThat(run.nodeResults.get(0).status()).isEqualTo("pending");
        assertThat(run.nodeResults.get(0).output()).isEmpty();
    }

    private static ApiDtos.WorkflowDef workflowWithOneNode(String nodeId) {
        var wf = new ApiDtos.WorkflowDef();
        wf.id = "wf-test";
        wf.name = "Containerless";
        wf.nodes = List.of(Map.<String, Object>of(
                "id", nodeId, "type", "start", "title", "Start"));
        wf.edges = List.of();
        return wf;
    }

    // ---------------------------------------------------------------- Stubs

    /** Fixed definition; the engine only reads it via the port. */
    /**
     * Agent creation without an LLM: yields an agent with a fixed answer.
     *
     * <p>This way the "start" node runs through without needing a model -
     * the test checks the wiring, not the agent logic.
     */
    private static final class StubAgents implements AgentInvocationPort {
        @Override
        public de.augmentia.quad.core.agent.Agent create(String agentId) {
            // Agent.execute(String) is final and runs via run(prompt, state).
            // So run(...) is overridden — that is where the stub answer lands.
            return new de.augmentia.quad.core.agent.Agent() {
                @Override
                public String run(String prompt, de.augmentia.quad.core.session.AgentSessionState state) {
                    return "stub-antwort";
                }

                @Override
                protected de.augmentia.quad.core.session.AgentSessionState newSessionState() {
                    return new de.augmentia.quad.core.session.AgentSessionState();
                }
            };
        }

        @Override
        public long timeoutMsOrZero(String agentId) {
            return 0L;
        }

        @Override
        public void sendToOutbound(String channelName, String message) {
        }
    }

    private record StaticDefinitions() implements WorkflowDefinitionPort {
        @Override
        public ApiDtos.WorkflowDef find(String workflowId) {
            return workflowWithOneNode("n1");
        }
    }

    /** Carries an arbitrary node type and counts its executions. */
    private static final class StubExecutor implements NodeExecutor {
        private final Set<String> types;

        StubExecutor(Set<String> types) {
            this.types = types;
        }

        @Override
        public Set<String> getTypes() {
            return types;
        }

        @Override
        public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                               List<Map<String, Object>> edges, WorkflowExecutionContext ctx,
                               NodeRunner runner) {
            return false;
        }
    }

    /** Node type "start" is never resolved via {@code registry}; the agent path counts here. */
    private static final class CountingExecutor implements NodeExecutor {
        int executions;

        @Override
        public Set<String> getTypes() {
            return Set.of("counting");
        }

        @Override
        public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                               List<Map<String, Object>> edges, WorkflowExecutionContext ctx,
                               NodeRunner runner) {
            executions++;
            return false;
        }
    }

    private static final class InMemoryRunPort implements WorkflowRunPort {
        private final Map<String, WorkflowRunState> runs = new ConcurrentHashMap<>();
        boolean saved;

        @Override
        public WorkflowRunState create(String workflowId, List<String> nodes, String initialData) {
            var state = WorkflowRunState.start("run-" + runs.size(), workflowId);
            state.initialData = initialData == null ? "" : initialData;
            for (var n : nodes) {
                state.nodeResults.add(new WorkflowNodeResult(n, "", "", "pending", "", ""));
            }
            runs.put(state.runId, state);
            return state;
        }

        @Override
        public WorkflowRunState load(String runId) {
            return runs.get(runId);
        }

        @Override
        public void save(WorkflowRunState state) {
            saved = true;
            runs.put(state.runId, state);
        }
    }

    private static final class InMemoryCheckpoints implements StepCheckpointPort {
        final Map<String, Checkpoint> saved = new ConcurrentHashMap<>();
        int loads;

        @Override
        public boolean save(String runId, int stepIndex, String runStatus,
                            String snapshotJson, String memorySummary) {
            saved.put(runId, new Checkpoint(runId, stepIndex, runStatus, snapshotJson, memorySummary));
            return true;
        }

        @Override
        public Optional<Checkpoint> loadLast(String runId) {
            loads++;
            return Optional.ofNullable(saved.get(runId));
        }
    }

    private static final class RecordingEvents implements RunEventPort {
        final List<String> phases = new java.util.ArrayList<>();
        long runCounter;

        @Override
        public void recordEvent(String runId, String eventType, String payloadJson) {
        }

        @Override
        public void audit(String id, String timestamp, String agent, String event, String details) {
        }

        @Override
        public void upsertRunSession(String runId, String task, String result,
                                     String memorySummary,
                                     de.augmentia.quad.core.session.AgentSessionState sessionState) {
        }

        @Override
        public void recordLifecycle(String runId, String phase,
                                    java.time.Instant timestamp) {
            phases.add(phase);
        }

        @Override
        public void recordStep(String runId, String stepId, String kind, String status, long durMs) {
        }

        @Override
        public long incrementRunCounter() {
            return ++runCounter;
        }
    }
}
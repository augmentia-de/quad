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
 * Beweist, dass die Engine ohne DI-Container laeuft.
 *
 * <p>Das ist die eigentliche Begruendung fuer den ganzen Umbau: der
 * Spring-Boot-Worker in {@code quad-workflow-worker} hat kein Arc, und genau
 * diese Klasse soll dort trotzdem benutzbar sein. Dieser Test baut die Engine
 * mit {@code new} und fuehrt einen Lauf aus - sobald wieder ein {@code @Inject}
 * ins Spiel kommt, fehlt der Container und die Konstruktion bricht sofort.
 *
 * <p>Die Ports sind hier triviale Stubs. Das ist Absicht: geprueft wird nicht
 * die Adapter-Seite, sondern dass die Engine keine Container-Kenntnis hat.
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
        // "start" wird vom AgentNodeExecutor behandelt, nicht vom Registry-Executor:
        // genau diese Fallback-Regel wird hier mitgeprueft.
        assertThat(executor.executions).isZero();
        // Der Run wurde ueber den Port persistiert, nicht direkt in eine Tabelle
        assertThat(runs.saved).isTrue();
        // Der Knoten hat tatsaechlich ein Ergebnis geschrieben
        assertThat(run.nodeResults).anyMatch(r -> "completed".equals(r.status())
                && "stub-antwort".equals(r.output()));
        // Lifecycle-Ereignisse sind ueber den Port gelaufen
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
        // Restore lief ueber den Port, nicht ueber einen direkten DB-Zugriff
        assertThat(checkpoints.loads).isEqualTo(1);
    }

    @Test
    void registryResolvesKnownTypesAndFallsBackForAgentPaths() {
        var loop = new StubExecutor(Set.of("loop"));
        var agent = new StubExecutor(Set.of("agent"));
        var registry = new NodeExecutorRegistry(List.of(loop, agent));

        assertThat(registry.forType("loop")).isSameAs(loop);
        assertThat(registry.forType("agent")).isSameAs(agent);
        // Spezialfaelle laufen ueber den Agent-Executor
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

    /** Feste Definition; die Engine liest sie nur ueber den Port. */
    /**
     * Agent-Erzeugung ohne LLM: liefert einen Agenten mit fester Antwort.
     *
     * <p>Damit laeuft der "start"-Knoten durch, ohne ein Modell zu brauchen -
     * der Test prueft die Verdrahtung, nicht die Agentenlogik.
     */
    private static final class StubAgents implements AgentInvocationPort {
        @Override
        public de.augmentia.quad.core.agent.Agent create(String agentId) {
            // Agent.execute(String) ist final und laeuft ueber run(prompt, state).
            // Also wird run(...) ueberschrieben - dort landet die Stub-Antwort.
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

    /** Traeger einen beliebigen Knotentyp und zaehlt seine Ausfuehrungen. */
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

    /** Knotentyp "start" wird ueber {@code registry} nie aufgeloest; der Agent-Pfad zaehlt hier. */
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
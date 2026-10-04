package de.augmentia.quad.quarkus.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@code WorkflowGeneratorService.addJoinNodes} — the DAG rewrite that
 * inserts {@code join} nodes so parallel branches actually merge.
 *
 * <p>This runs after {@code toWorkflowDef} has built the step nodes and edges, and it
 * silently restructures the graph: it rewires predecessors into a join and can add a
 * final aggregator. A mistake here produces a workflow that looks valid and then
 * deadlocks at runtime, which is why it is worth pinning down without an LLM.
 */
class WorkflowGeneratorJoinTest {

    private final WorkflowGeneratorService svc = new WorkflowGeneratorService();

    // ── fixture helpers mirroring toWorkflowDef's node/edge shape ────────────

    private static Map<String, Object> stepNode(String nodeId, String title) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", nodeId);
        n.put("type", "agent");
        n.put("title", title);
        n.put("status", "pending");
        n.put("config", new LinkedHashMap<String, Object>());
        return n;
    }

    private static Map<String, Object> edge(String source, String target) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceNodeId", source);
        input.put("format", "json");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("input", input);
        e.put("joinPolicy", "all");
        return e;
    }

    private static WorkflowGeneratorService.GeneratedStep step(String role, List<String> deps) {
        return new WorkflowGeneratorService.GeneratedStep(
            role, "do " + role, List.of(), deps, 120, null, null);
    }

    /** A graph plus the role→node mapping addJoinNodes expects. */
    private record Graph(List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                         Map<String, String> roleToNodeId,
                         List<WorkflowGeneratorService.GeneratedStep> steps) {}

    private static Graph graphOf(WorkflowGeneratorService.GeneratedStep... steps) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, String> roleToNodeId = new LinkedHashMap<>();
        List<WorkflowGeneratorService.GeneratedStep> list = new ArrayList<>();
        for (var s : steps) {
            String nodeId = "node-" + s.agentRole();
            roleToNodeId.put(s.agentRole(), nodeId);
            nodes.add(stepNode(nodeId, s.agentRole()));
            for (String dep : s.dependsOn()) {
                edges.add(edge(roleToNodeId.get(dep), nodeId));
            }
            list.add(s);
        }
        return new Graph(nodes, edges, roleToNodeId, list);
    }

    private static void run(Graph g) {
        new WorkflowGeneratorService().addJoinNodes(g.nodes(), g.edges(), g.steps(), g.roleToNodeId(), 120_000L);
    }

    private static List<String> idsOfType(List<Map<String, Object>> nodes, String type) {
        List<String> out = new ArrayList<>();
        for (var n : nodes) {
            if (type.equals(n.get("type"))) out.add(String.valueOf(n.get("id")));
        }
        return out;
    }

    private static List<String> targetsOf(List<Map<String, Object>> edges, String source) {
        List<String> out = new ArrayList<>();
        for (var e : edges) {
            if (source.equals(String.valueOf(e.get("source")))) out.add(String.valueOf(e.get("target")));
        }
        return out;
    }

    private static List<String> sourcesOf(List<Map<String, Object>> edges, String target) {
        List<String> out = new ArrayList<>();
        for (var e : edges) {
            if (target.equals(String.valueOf(e.get("target")))) out.add(String.valueOf(e.get("source")));
        }
        return out;
    }

    // ── merge points ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("inserts a join before a step with two predecessors")
    void mergePoint() {
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")),
            step("Join", List.of("Left", "Right")));
        run(g);

        List<String> joins = idsOfType(g.nodes(), "join");
        assertEquals(1, joins.size(), "expected exactly one merge join, got " + joins);

        String joinId = joins.get(0);
        // Both branches now feed the join, and the join feeds the consumer.
        assertTrue(targetsOf(g.edges(), "node-Left").contains(joinId));
        assertTrue(targetsOf(g.edges(), "node-Right").contains(joinId));
        assertTrue(targetsOf(g.edges(), joinId).contains("node-Join"));
        // The direct branch→consumer edges are gone.
        assertFalse(targetsOf(g.edges(), "node-Left").contains("node-Join"));
        assertFalse(targetsOf(g.edges(), "node-Right").contains("node-Join"));
    }

    @Test
    @DisplayName("a single predecessor needs no join")
    void singlePredecessor() {
        var g = graphOf(step("A", List.of()), step("B", List.of("A")));
        run(g);
        assertTrue(idsOfType(g.nodes(), "join").isEmpty());
        assertEquals(1, g.edges().size());
    }

    @Test
    @DisplayName("an isolated single step needs no join")
    void singleNode() {
        var g = graphOf(step("Only", List.of()));
        run(g);
        assertTrue(idsOfType(g.nodes(), "join").isEmpty());
    }

    // ── final aggregator for parallel chains that never merge ─────────────────

    @Test
    @DisplayName("adds a final aggregator when parallel chains never merge")
    void finalAggregator() {
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")));
        run(g);

        List<String> joins = idsOfType(g.nodes(), "join");
        assertEquals(1, joins.size(), "expected a final aggregator, got " + joins);
        String joinId = joins.get(0);
        assertTrue(targetsOf(g.edges(), "node-Left").contains(joinId));
        assertTrue(targetsOf(g.edges(), "node-Right").contains(joinId));
    }

    @Test
    @DisplayName("no final aggregator for a single terminal step")
    void noAggregatorForSingleTerminal() {
        var g = graphOf(step("A", List.of()), step("B", List.of("A")));
        run(g);
        assertTrue(idsOfType(g.nodes(), "join").isEmpty());
    }

    // ── fork handling ────────────────────────────────────────────────────────

    @Test
    @DisplayName("broadcast to purely terminal targets gets no join")
    void forkToTerminalsOnly() {
        // Root fans out to two leaves that never merge and have no successors:
        // terminal parallelism, no aggregation needed.
        var g = graphOf(
            step("Root", List.of()),
            step("LeafA", List.of("Root")),
            step("LeafB", List.of("Root")),
            step("LeafC", List.of("Root")));
        run(g);
        // Only the final aggregator over the three leaves may appear.
        for (String id : idsOfType(g.nodes(), "join")) {
            assertTrue(targetsOf(g.edges(), "node-LeafA").contains(id)
                    || targetsOf(g.edges(), "node-LeafB").contains(id)
                    || targetsOf(g.edges(), "node-LeafC").contains(id));
        }
    }

    @Test
    @DisplayName("a fork whose targets continue gets an aggregating join")
    void forkWithContinuation() {
        // Left continues, Right terminates: the fork needs an aggregation point so the
        // continuing branch does not run before the other one produced something.
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")),
            step("After", List.of("Left")));
        run(g);

        List<String> joins = idsOfType(g.nodes(), "join");
        assertTrue(joins.size() >= 1, "expected at least one join, got " + joins);
    }

    // ── id uniqueness ────────────────────────────────────────────────────────

    @Test
    @DisplayName("join ids never collide with existing node ids")
    void joinIdsUnique() {
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")),
            step("Join", List.of("Left", "Right")));
        // Pre-create the id the first join would take.
        g.nodes().add(stepNode("node-join", "Preexisting"));
        run(g);

        List<String> allIds = new ArrayList<>();
        for (var n : g.nodes()) allIds.add(String.valueOf(n.get("id")));
        assertEquals(allIds.size(), allIds.stream().distinct().count(),
            "duplicate node ids after join insertion: " + allIds);
    }

    @Test
    @DisplayName("every join aggregates at least two results")
    void joinsAggregateAtLeastTwo() {
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")),
            step("Join", List.of("Left", "Right")));
        run(g);

        for (var n : g.nodes()) {
            if (!"join".equals(n.get("type"))) continue;
            @SuppressWarnings("unchecked")
            var config = (Map<String, Object>) n.get("config");
            @SuppressWarnings("unchecked")
            var resultIds = (List<String>) config.get("resultIds");
            assertTrue(resultIds.size() >= 2,
                "join " + n.get("id") + " aggregates only " + resultIds);
        }
    }

    @Test
    @DisplayName("join config carries the timeout it was given")
    void joinCarriesTimeout() {
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")),
            step("Join", List.of("Left", "Right")));
        new WorkflowGeneratorService()
            .addJoinNodes(g.nodes(), g.edges(), g.steps(), g.roleToNodeId(), 300_000L);

        for (var n : g.nodes()) {
            if (!"join".equals(n.get("type"))) continue;
            @SuppressWarnings("unchecked")
            var config = (Map<String, Object>) n.get("config");
            assertEquals(300_000L, config.get("timeout"));
        }
    }

    @Test
    @DisplayName("the graph stays connected: every non-root step has a predecessor")
    void noOrphanNodes() {
        var g = graphOf(
            step("Root", List.of()),
            step("Left", List.of("Root")),
            step("Right", List.of("Root")),
            step("Join", List.of("Left", "Right")),
            step("Tail", List.of("Join")));
        run(g);

        for (String nodeId : g.roleToNodeId().values()) {
            if (!sourcesOf(g.edges(), nodeId).isEmpty()) continue;
            // Only the graph root may have no incoming edge.
            assertEquals("node-Root", nodeId,
                "step node " + nodeId + " ended up without a predecessor");
        }
    }
}
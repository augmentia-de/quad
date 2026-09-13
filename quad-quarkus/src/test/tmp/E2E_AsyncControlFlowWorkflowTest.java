package de.augmentia.quad.quarkus.itest;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;

import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E backend test against a RUNNING backend (plain REST, real LLM + DB).
 *
 * <p>Covers the async / parallel control-flow features of the workflow engine:</p>
 * <ul>
 *   <li><b>async + deferred completion:</b> an {@code async} node runs its child on a separate
 *       virtual thread; the run proceeds; the result is additionally delivered through the
 *       deferred-completion channel {@code POST /api/ui/dynamic/complete} (external REST-resumable,
 *       late callbacks recorded, unknown runs rejected).</li>
 *   <li><b>fork / join:</b> parallel branches run concurrently and are merged by a join node.</li>
 *   <li><b>loop:</b> repeats its body until {@code exitCondition} (here {@code iter>=2}).</li>
 *   <li><b>conditional:</b> routes to {@code trueTargetId} / {@code falseTargetId}; the not-taken
 *       branch is recorded as skipped.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_AsyncControlFlowWorkflowTest {

    private static String feedAgentId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    private static String createAgent(String name) {
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"category\":\"general\",\"model\":\"gpt-4o-mini\","
                + "\"temperature\":0.2,\"maxTokens\":1024,\"topP\":1.0,\"tools\":[],"
                + "\"guardrailsInput\":[],\"guardrailsOutput\":[]}")
        .when().post("/api/ui/agents")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    private static String agentNode(String id, String title, String prompt) {
        return "{\"id\":\"" + id + "\",\"type\":\"agent\",\"title\":\"" + title + "\","
            + "\"config\":{\"agentId\":\"" + feedAgentId + "\",\"prompt\":\"" + prompt + "\"}}";
    }

    private static String createWorkflow(String name, String nodes, String edges) {
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    private static String startRun(String workflowId, String initialData) {
        return given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"initialData\":\"" + initialData + "\"}")
        .when().post("/api/ui/workflows/" + workflowId + "/execute")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNode(List<?> results, String nodeId) {
        for (Object r : results) {
            Map<String, Object> m = (Map<String, Object>) r;
            if (nodeId.equals(String.valueOf(m.get("nodeId")))) return m;
        }
        return null;
    }

    private static String outputOf(Map<String, Object> node) {
        return node != null ? String.valueOf(node.get("output")) : null;
    }

    // ───────────────────────────────────────────────────────────────────────
    // 1. Shared feed agent
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createFeedAgent() {
        feedAgentId = createAgent("E2E-ControlFlow-Feed");
        given().when().get("/api/ui/agents/" + feedAgentId).then().statusCode(200);
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2. async node + deferred completion
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void asyncStep_runsChild_concurrentAndDeferredCompletion() throws Exception {
        String wfId = createWorkflow("E2E Async Workflow",
            agentNode("n1", "Vorbereiten", "Antworte nur mit: DONE")
                + "," + "{\"id\":\"a1\",\"type\":\"async\",\"title\":\"Async Schritt\","
                + "\"config\":{\"refNodeId\":\"achild\",\"timeoutMs\":90000}}"
                + "," + agentNode("achild", "Async Kind", "Antworte nur mit: ASYNC-ERGEBNIS")
                + "," + agentNode("n2", "Abschluss", "Nenne die Eingabe"),
            "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"a1\"}"
                + "," + "{\"id\":\"e2\",\"source\":\"a1\",\"target\":\"achild\"}"
                + "," + "{\"id\":\"e3\",\"source\":\"a1\",\"target\":\"n2\"}");

        String runId = startRun(wfId, "async auftrag 42");
        assertEquals("completed", await(runId).get("status"), "async workflow must complete; run " + runId);

        List<?> results = (List<?>) await(runId).get("nodeResults");
        Map<String, Object> a1 = findNode(results, "a1");
        assertNotNull(a1, "async node must be recorded: " + results);
        String a1Out = outputOf(a1);
        assertNotNull(a1Out);
        assertFalse(a1Out.contains("deferred completion timed out"), "async node must resolve, got: " + a1Out);
        assertFalse(a1Out.contains("no child node"), a1Out);

        // Deferred completion REST contract: accepted while running AND as late callback.
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"runId\":\"" + runId + "\",\"nodeId\":\"a1\",\"output\":\"EXTERNAL-1\"}")
        .when().post("/api/ui/dynamic/complete")
        .then().statusCode(200).body("ok", is(true));

        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"runId\":\"" + runId + "\",\"nodeId\":\"a1\",\"output\":\"EXTERNAL-2\"}")
        .when().post("/api/ui/dynamic/complete")
        .then().statusCode(200).body("ok", is(true));

        // Unknown run / missing runId are rejected.
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"runId\":\"does-not-exist\",\"nodeId\":\"a1\"}")
        .when().post("/api/ui/dynamic/complete")
        .then().statusCode(404);
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"nodeId\":\"a1\"}")
        .when().post("/api/ui/dynamic/complete")
        .then().statusCode(400);

        given().when().delete("/api/ui/workflows/" + wfId).then().statusCode(204);
    }

    // ───────────────────────────────────────────────────────────────────────
    // 3. fork / join (parallel branches)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void forkJoin_parallelBranchesMergedByJoin() throws Exception {
        String wfId = createWorkflow("E2E Fork/Join",
            agentNode("n1", "Start", "Antworte nur mit: WURZEL")
                + "," + "{\"id\":\"fk\",\"type\":\"fork\",\"title\":\"Fork\"}"
                + "," + agentNode("nA", "Zweig A", "Antworte nur mit: ZWEIG-A")
                + "," + agentNode("nB", "Zweig B", "Antworte nur mit: ZWEIG-B")
                + "," + "{\"id\":\"jn\",\"type\":\"join\",\"title\":\"Join\","
                + "\"config\":{\"resultIds\":[\"nA\",\"nB\"],\"timeout\":20000}}"
                + "," + agentNode("nC", "Aggregator", "Fasse die Eingabe zusammen"),
            "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"fk\"}"
                + "," + "{\"id\":\"e2\",\"source\":\"fk\",\"target\":\"nA\"}"
                + "," + "{\"id\":\"e3\",\"source\":\"fk\",\"target\":\"nB\"}"
                + "," + "{\"id\":\"e4\",\"source\":\"nA\",\"target\":\"jn\","
                + "\"input\":{\"sourceNodeId\":\"nA\",\"format\":\"text\"}}"
                + "," + "{\"id\":\"e5\",\"source\":\"nB\",\"target\":\"jn\","
                + "\"input\":{\"sourceNodeId\":\"nB\",\"format\":\"text\"}}"
                + "," + "{\"id\":\"e6\",\"source\":\"jn\",\"target\":\"nC\","
                + "\"input\":{\"sourceNodeId\":\"jn\",\"format\":\"text\"}}");

        String runId = startRun(wfId, "parallel 1");
        Map<String, Object> run = await(runId);
        assertEquals("completed", String.valueOf(run.get("status")), "fork/join run must complete");

        List<?> results = (List<?>) run.get("nodeResults");
        Map<String, Object> fk = findNode(results, "fk");
        Map<String, Object> nA = findNode(results, "nA");
        Map<String, Object> nB = findNode(results, "nB");
        Map<String, Object> jn = findNode(results, "jn");
        Map<String, Object> nC = findNode(results, "nC");
        assertNotNull(fk, "fork node must be recorded: " + results);
        assertNotNull(nA, "branch A must be recorded");
        assertNotNull(nB, "branch B must be recorded");
        assertNotNull(jn, "join node must be recorded");
        assertNotNull(nC, "aggregator must be recorded");

        // Every branch produced a real (non-skipped) result.
        assertFalse(outputOf(nA).startsWith("[skipped"), "branch A must run, got: " + outputOf(nA));
        assertTrue(outputOf(nA).contains("ZWEIG-A"), "branch A output: " + outputOf(nA));
        assertFalse(outputOf(nB).startsWith("[skipped"), "branch B must run, got: " + outputOf(nB));
        assertTrue(outputOf(nB).contains("ZWEIG-B"), "branch B output: " + outputOf(nB));

        // Join aggregated BOTH branch outputs.
        String joinOut = outputOf(jn);
        assertNotNull(joinOut);
        assertTrue(joinOut.contains("[nA]"), "join must contain branch A result: " + joinOut);
        assertTrue(joinOut.contains("[nB]"), "join must contain branch B result: " + joinOut);

        // Aggregator consumed the join output.
        assertTrue(String.valueOf(nC.get("input")).contains("[jn]"),
            "aggregator must consume the join node input: " + nC.get("input"));

        given().when().delete("/api/ui/workflows/" + wfId).then().statusCode(204);
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4. loop (iteration until exitCondition)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void loop_earlyExit_atIterationCondition() throws Exception {
        String wfId = createWorkflow("E2E Loop",
            agentNode("n1", "Feed", "Antworte nur mit: SCHLEIFE")
                + "," + "{\"id\":\"loop\",\"type\":\"loop\",\"title\":\"Loop\","
                + "\"config\":{\"maxIterations\":5,\"exitCondition\":\"iter>=2\"}}"
                + "," + agentNode("n2", "Body", "Antworte nur mit: ITERATION"),
            "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"loop\"}"
                + "," + "{\"id\":\"e2\",\"source\":\"loop\",\"target\":\"n2\"}");

        String runId = startRun(wfId, "loop auftrag");
        Map<String, Object> run = await(runId);
        assertEquals("completed", String.valueOf(run.get("status")), "loop run must complete");

        List<?> results = (List<?>) run.get("nodeResults");
        Map<String, Object> loop = findNode(results, "loop");
        assertNotNull(loop, "loop node must be recorded: " + results);
        String loopOut = outputOf(loop);
        assertTrue(loopOut.contains("Loop iterations=2"), "expected early exit at 2, got: " + loopOut);
        assertTrue(loopOut.contains("exitReached=true"), loopOut);

        given().when().delete("/api/ui/workflows/" + wfId).then().statusCode(204);
    }

    // ───────────────────────────────────────────────────────────────────────
    // 5. conditional (true/false routing, skipped branch)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void conditional_routesTrueAndFalseBranches() throws Exception {
        String wfId = createWorkflow("E2E Conditional",
            "{\"id\":\"c1\",\"type\":\"conditional\",\"title\":\"Bedingung\","
                + "\"config\":{\"condition\":\"contains:WIN\",\"trueTargetId\":\"nT\",\"falseTargetId\":\"nF\"}}"
                + "," + agentNode("nT", "Treffer", "Antworte nur mit: TRUE-BRANCH")
                + "," + agentNode("nF", "Sonst", "Antworte nur mit: FALSE-BRANCH"),
            "{\"id\":\"e1\",\"source\":\"c1\",\"target\":\"nT\"}"
                + "," + "{\"id\":\"e2\",\"source\":\"c1\",\"target\":\"nF\"}");

        // Run A — condition met (initialData contains WIN).
        Map<String, Object> runA = await(startRun(wfId, "Meldung mit WIN im Text"));
        assertEquals("completed", String.valueOf(runA.get("status")));
        Map<String, Object> c1A = findNode((List<?>) runA.get("nodeResults"), "c1");
        Map<String, Object> nTA = findNode((List<?>) runA.get("nodeResults"), "nT");
        Map<String, Object> nFA = findNode((List<?>) runA.get("nodeResults"), "nF");
        assertTrue(outputOf(c1A).contains("condition met"));
        assertTrue(outputOf(nTA).contains("TRUE-BRANCH"), "true branch must run: " + outputOf(nTA));
        assertEquals("[skipped by conditional c1]", outputOf(nFA));

        // Run B — condition not met → false branch runs, true branch skipped.
        Map<String, Object> runB = await(startRun(wfId, "ganz normaler text"));
        assertEquals("completed", String.valueOf(runB.get("status")));
        Map<String, Object> c1B = findNode((List<?>) runB.get("nodeResults"), "c1");
        Map<String, Object> nTB = findNode((List<?>) runB.get("nodeResults"), "nT");
        Map<String, Object> nFB = findNode((List<?>) runB.get("nodeResults"), "nF");
        assertTrue(outputOf(c1B).contains("condition not met"));
        assertEquals("[skipped by conditional c1]", outputOf(nTB));
        assertTrue(outputOf(nFB).contains("FALSE-BRANCH"), "false branch must run: " + outputOf(nFB));

        given().when().delete("/api/ui/workflows/" + wfId).then().statusCode(204);
    }

    @Test
    @Order(6)
    void cleanup() {
        given().when().delete("/api/ui/agents/" + feedAgentId).then().statusCode(204);
        assertRowAbsent("agents", "id = ?", feedAgentId, "feed agent should be deleted from DB");
    }

    private static Map<String, Object> await(String runId) throws Exception {
        return waitForRun(runId, 240);
    }
}
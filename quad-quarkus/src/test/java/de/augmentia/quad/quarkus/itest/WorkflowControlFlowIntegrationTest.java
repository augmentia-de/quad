package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Tag("llm")
class WorkflowControlFlowIntegrationTest {

    private static final RestAssuredConfig TIMEOUT = RestAssuredConfig.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 120000)
            .setParam("http.connection.timeout", 10000));

    private static final String AGENT = "{\"id\":\"%s\",\"type\":\"%s\",\"title\":\"%s\",\"status\":\"pending\"}";

    private String createWorkflow(String body) {
        return given().contentType(ContentType.JSON).body(body)
            .when().post("/api/ui/workflows")
            .then().statusCode(201).extract().path("id");
    }

    private void deleteWorkflow(String id) {
        given().when().delete("/api/ui/workflows/" + id);
    }

    private String startRun(String wfId, String body) {
        return given()
            .contentType(ContentType.JSON)
            .body(body)
            .config(TIMEOUT)
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200)
            .body("runId", is(notNullValue()))
            .extract().path("runId");
    }

    private Map<String, Object> awaitRun(String runId) throws Exception {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given()
                .when().get("/api/ui/runs/" + runId)
                .then().statusCode(200).extract().as(Map.class);
            String status = (String) run.get("status");
            if ("completed".equals(status) || "failed".equals(status)) return run;
        }
        fail("Run did not finish within 60s: " + runId);
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodeResults(Map<String, Object> run) {
        return (List<Map<String, Object>>) run.get("nodeResults");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeOut(List<Map<String, Object>> results, String nodeId) {
        return results.stream()
            .filter(r -> nodeId.equals(r.get("nodeId")))
            .findFirst().orElse(null);
    }

    private static String agent(String id, String title) {
        return String.format(AGENT, id, "research", title);
    }

    @Test
    void initialData_isFedToRootNode() throws Exception {
        String wfId = createWorkflow("{\"name\":\"InitialData\",\"nodes\":["
            + agent("n1", "Root Agent") + "],\"edges\":[]}");

        String runId = startRun(wfId, "{\"initialData\":\"INIT:SECRET123\"}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));
        assertEquals("INIT:SECRET123", run.get("initialData"));

        var n1 = nodeOut(nodeResults(run), "n1");
        assertNotNull(n1);
        String input = (String) n1.get("input");
        assertTrue(input.contains("\"initialData\":\"INIT:SECRET123\""),
            "Root node input should carry the initial data as structured JSON envelope, got: " + input);
        assertTrue(input.contains("\"workflow\""), "Envelope should include the workflow block, got: " + input);
        assertTrue(input.contains("\"node\""), "Envelope should include the node block, got: " + input);

        deleteWorkflow(wfId);
    }

    @Test
    void loop_reachesMaxIterations_whenExitConditionNeverMatches() throws Exception {
        String wfId = createWorkflow("{\"name\":\"Loop Max\",\"nodes\":["
            + agent("n1", "Feed")
            + ",{\"id\":\"loop\",\"type\":\"loop\",\"title\":\"Loop\",\"config\":{\"maxIterations\":3,\"exitCondition\":\"contains:NEVER\"},\"status\":\"pending\"}"
            + "," + agent("n2", "Body")
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"loop\"},"
            + "{\"id\":\"e2\",\"source\":\"loop\",\"target\":\"n2\"}]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var loop = nodeOut(nodeResults(run), "loop");
        assertNotNull(loop);
        String loopOut = (String) loop.get("output");
        assertTrue(loopOut.contains("Loop iterations=3"), "expected 3 iterations, got: " + loopOut);
        assertTrue(loopOut.contains("maxIterationsReached=true"), loopOut);

        deleteWorkflow(wfId);
    }

    @Test
    void loop_exitsEarly_whenIterationConditionMet() throws Exception {
        String wfId = createWorkflow("{\"name\":\"Loop Exit\",\"nodes\":["
            + agent("n1", "Feed")
            + ",{\"id\":\"loop\",\"type\":\"loop\",\"title\":\"Loop\",\"config\":{\"maxIterations\":5,\"exitCondition\":\"iter>=2\"},\"status\":\"pending\"}"
            + "," + agent("n2", "Body")
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"loop\"},"
            + "{\"id\":\"e2\",\"source\":\"loop\",\"target\":\"n2\"}]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var loop = nodeOut(nodeResults(run), "loop");
        assertNotNull(loop);
        String loopOut = (String) loop.get("output");
        assertTrue(loopOut.contains("Loop iterations=2"), "expected early exit at 2, got: " + loopOut);
        assertTrue(loopOut.contains("exitReached=true"), loopOut);

        deleteWorkflow(wfId);
    }

    @Test
    void loop_withoutAnchor_loopsOverEntireDownstreamSegment() throws Exception {
        // loop -> n2 -> n3 (two body agents). No loopTargetId set: the loop body
        // must enclose BOTH agents and repeat them each iteration.
        String wfId = createWorkflow("{\"name\":\"Loop Multi Body\",\"nodes\":["
            + agent("n1", "Feed")
            + ",{\"id\":\"loop\",\"type\":\"loop\",\"title\":\"Loop\",\"config\":{\"maxIterations\":3,\"exitCondition\":\"contains:NEVER\"},\"status\":\"pending\"}"
            + "," + agent("n2", "Body A")
            + "," + agent("n3", "Body B")
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"loop\"},"
            + "{\"id\":\"e2\",\"source\":\"loop\",\"target\":\"n2\"},"
            + "{\"id\":\"e3\",\"source\":\"n2\",\"target\":\"n3\"}]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var loop = nodeOut(nodeResults(run), "loop");
        assertNotNull(loop);
        String loopOut = (String) loop.get("output");
        assertTrue(loopOut.contains("Loop iterations=3"), "expected 3 iterations, got: " + loopOut);

        // Both body agents must have real outputs (not skipped), proving both are in the loop body
        var n2 = nodeOut(nodeResults(run), "n2");
        var n3 = nodeOut(nodeResults(run), "n3");
        assertNotNull(n2);
        assertNotNull(n3);
        assertNotEquals("[skipped by conditional loop]", n2.get("output"));
        assertNotEquals("[skipped by conditional loop]", n3.get("output"));

        deleteWorkflow(wfId);
    }

    @Test
    void conditional_skipsDownstream_whenConditionNotMet() throws Exception {
        String wfId = createWorkflow("{\"name\":\"Cond Skip\",\"nodes\":["
            + agent("n1", "Feed")
            + ",{\"id\":\"c1\",\"type\":\"conditional\",\"title\":\"Check\",\"config\":{\"condition\":\"contains:NEVER\"},\"status\":\"pending\"}"
            + "," + agent("n2", "Downstream")
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"c1\"},"
            + "{\"id\":\"e2\",\"source\":\"c1\",\"target\":\"n2\"}]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var c1 = nodeOut(nodeResults(run), "c1");
        assertNotNull(c1);
        assertTrue(((String) c1.get("output")).contains("condition not met"));

        var n2 = nodeOut(nodeResults(run), "n2");
        assertNotNull(n2);
        assertEquals("[skipped by conditional c1]", n2.get("output"));

        deleteWorkflow(wfId);
    }

    @Test
    void conditional_runsDownstream_whenConditionMet() throws Exception {
        // Condition "always" is deterministic-true; "contains:LLM" was flaky because it
        // matched against the LLM-generated feed output. The routing semantics are identical:
        // a true condition must run (not skip) the downstream agent.
        String wfId = createWorkflow("{\"name\":\"Cond Met\",\"nodes\":["
            + agent("n1", "Feed")
            + ",{\"id\":\"c1\",\"type\":\"conditional\",\"title\":\"Check\",\"config\":{\"condition\":\"always\"},\"status\":\"pending\"}"
            + "," + agent("n2", "Downstream")
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"c1\"},"
            + "{\"id\":\"e2\",\"source\":\"c1\",\"target\":\"n2\"}]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var c1 = nodeOut(nodeResults(run), "c1");
        assertNotNull(c1);
        assertTrue(((String) c1.get("output")).contains("condition met"));

        var n2 = nodeOut(nodeResults(run), "n2");
        assertNotNull(n2);
        assertNotEquals("[skipped by conditional c1]", n2.get("output"));

        deleteWorkflow(wfId);
    }

    @Test
    void nestedWorkflow_runsSubWorkflow_withOutputMapping() throws Exception {
        String subId = createWorkflow("{\"name\":\"Sub WF\",\"nodes\":["
            + agent("s1", "Sub Step") + "],\"edges\":[]}");

        String wfId = createWorkflow("{\"name\":\"Nested Parent\",\"nodes\":["
            + agent("n1", "Feed")
            + ",{\"id\":\"nest\",\"type\":\"nested-workflow\",\"title\":\"Nested\","
            + "\"config\":{\"workflowId\":\"" + subId + "\",\"outputMapping\":{\"s1\":\"summary\"}},\"status\":\"pending\"}"
            + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"nest\"}]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var nest = nodeOut(nodeResults(run), "nest");
        assertNotNull(nest);
        String nestOut = (String) nest.get("output");
        assertTrue(nestOut.contains("Nested workflow: Sub WF"), nestOut);
        assertTrue(nestOut.contains("summary = "), nestOut);

        deleteWorkflow(wfId);
        deleteWorkflow(subId);
    }

    @Test
    void nestedWorkflow_missingWorkflowId_returnsHint() throws Exception {
        String wfId = createWorkflow("{\"name\":\"Nested Missing\",\"nodes\":["
            + "{\"id\":\"nest\",\"type\":\"nested-workflow\",\"title\":\"Nested\",\"config\":{},\"status\":\"pending\"}"
            + "],\"edges\":[]}");

        String runId = startRun(wfId, "{}");
        var run = awaitRun(runId);
        assertEquals("completed", run.get("status"));

        var nest = nodeOut(nodeResults(run), "nest");
        assertNotNull(nest);
        assertTrue(((String) nest.get("output")).contains("no workflowId configured"));

        deleteWorkflow(wfId);
    }
}
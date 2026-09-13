package de.augmentia.quad.quarkus.itest;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E backend test against a RUNNING backend (plain REST, no Quarkus test container, no dummy
 * agents). Requires the backend to be up (start via its main class / {@code mvn quarkus:dev}) and
 * to have a real LLM + database configured.
 *
 * <p>Flow: generate a workflow from a task via /dynamic → validate → run (dynamic) → save
 * (persists a row) → re-run it from the classic Workflow module → verify audit logs and the exact
 * DB rows (workflows + runs).
 *
 * <p><b>Known live-e2e flake:</b> {@code generate} is LLM-driven (Workflow-Architect). If the
 * orchestrator answer contains an inconsistent step reference (e.g. a typo in a step name),
 * {@code WorkflowGeneratorService.validateSteps} rejects it and all retries may fail → the test
 * gets a 500 instead of the expected 200. Re-run when the LLM output is healthy.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_DynamicWorkflowTest {

    private static final String TASK =
        "Research the e-scooter market, summarize the key findings "
        + "and create a 1-page product briefing from them.";

    private static Map<String, Object> generated;   // { workflow: … } from /generate
    private static String dynWorkflowId;
    private static String savedWorkflowId;
    private static String dynRunId;
    private static String classicRunId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    @Test
    @Order(1)
    void generateAndValidateWorkflow() {
        generated = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"task\":\"" + TASK + "\",\"name\":\"E2E Dynamic Marktanalyse\"}")
        .when().post("/api/ui/dynamic/generate")
        .then().statusCode(200).body("workflow", is(notNullValue()))
            .extract().as(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> wf = (Map<String, Object>) generated.get("workflow");
        dynWorkflowId = String.valueOf(wf.get("id"));
        assertTrue(dynWorkflowId.startsWith("workflow-dyn-"), "Expected dynamic workflow id, got " + dynWorkflowId);

        List<?> nodes = (List<?>) wf.get("nodes");
        List<?> edges = (List<?>) wf.get("edges");
        assertNotNull(nodes);
        assertTrue(nodes.size() >= 2, "Generated workflow should have at least 2 steps, got " + nodes.size());
        assertNotNull(edges);

        Set<String> nodeIds = new HashSet<>();
        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            String id = String.valueOf(node.get("id"));
            assertTrue(nodeIds.add(id), "Duplicate node id: " + id);
            String type = String.valueOf(node.get("type"));
            // The architect may emit agent steps plus orchestration nodes (join/fork/loop/…).
            assertTrue(Set.of("agent", "join", "fork", "loop", "conditional").contains(type),
                "Unexpected generated node type: " + type);
            assertNotNull(node.get("title"));
            if ("agent".equals(type)) {
                Map<?, ?> cfg = (Map<?, ?>) node.get("config");
                // Each step executes on its own dedicated per-step agent (agent-<hex>).
                String agentId = String.valueOf(cfg.get("agentId"));
                assertTrue(agentId.startsWith("agent-"),
                    "agent node must reference a dedicated per-step agent via config.agentId, got: " + agentId);
            }
        }
        for (Object e : edges) {
            Map<?, ?> edge = (Map<?, ?>) e;
            String src = String.valueOf(edge.get("source"));
            String tgt = String.valueOf(edge.get("target"));
            assertTrue(nodeIds.contains(src), "Edge source " + src + " is not a node");
            assertTrue(nodeIds.contains(tgt), "Edge target " + tgt + " is not a node");
        }
    }

    @Test
    @Order(2)
    void runDynamicWorkflow() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> wf = (Map<String, Object>) generated.get("workflow");

        dynRunId = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"workflow\":" + mapToJson(wf) + ",\"initialData\":\"" + TASK + "\"}")
        .when().post("/api/ui/dynamic/run")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");

        Map<String, Object> run = waitForRun(dynRunId, 240);
        assertEquals("completed", String.valueOf(run.get("status")),
            "Dynamic workflow should complete; run " + dynRunId);
        assertTrue(((List<?>) run.get("nodeResults")).size() >= 2,
            "All generated steps should be recorded");

        Map<String, String> row = requireRow(runRow(dynRunId), "dynamic run row");
        assertEquals(dynWorkflowId, row.get("workflow_id"));
    }

    @Test
    @Order(3)
    void savePersistsWorkflowToDb() {
        @SuppressWarnings("unchecked")
        Map<String, Object> wf = (Map<String, Object>) generated.get("workflow");

        savedWorkflowId = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"name\":\"E2E Dynamic Marktanalyse\",\"nodes\":"
                + mapToJson(wf.get("nodes")) + ",\"edges\":" + mapToJson(wf.get("edges"))
                + ",\"initialTask\":\"" + TASK + "\"}")
        .when().post("/api/ui/dynamic/save")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
        assertTrue(savedWorkflowId.startsWith("workflow-"), "Saved workflow id must be a classic workflow id");

        Map<String, String> row = requireRow(workflowRow(savedWorkflowId), "saved workflow row");
        assertEquals("E2E Dynamic Marktanalyse", row.get("name"));
        assertTrue(row.get("nodes").contains("\"agent\""), "persisted nodes payload missing agent type");

        given().when().get("/api/ui/workflows/" + savedWorkflowId)
            .then().statusCode(200).body("name", is("E2E Dynamic Marktanalyse"));
    }

    @Test
    @Order(4)
    void rerunInClassicWorkflowModule() throws Exception {
        classicRunId = given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + savedWorkflowId + "/execute")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");

        Map<String, Object> run = waitForRun(classicRunId, 240);
        assertEquals("completed", String.valueOf(run.get("status")),
            "Classic-mode run should complete; run " + classicRunId);

        Map<String, String> row = requireRow(runRow(classicRunId), "classic run row");
        assertEquals(savedWorkflowId, row.get("workflow_id"));

        assertNotNull(runRow(dynRunId), "dynamic run should still be present in DB");
        assertTrue(countRunsForWorkflow(savedWorkflowId) >= 1,
            "expected >=1 persisted run for saved workflow");
    }

    @Test
    @Order(5)
    void verifyLogsAndCleanup() {
        List<?> logs = given().when().get("/api/ui/audit/logs")
            .then().statusCode(200).extract().as(List.class);

        List<?> create = logs.stream()
            .filter(l -> "Create".equals(String.valueOf(((Map<?, ?>) l).get("event"))))
            .toList();
        assertFalse(create.isEmpty(), "Audit must contain 'Create' entries");

        List<?> runStarted = logs.stream()
            .filter(l -> String.valueOf(((Map<?, ?>) l).get("event")).contains("Workflow Run Started"))
            .toList();
        assertTrue(runStarted.size() >= 2,
            "Audit must contain run-started entries for both executions, got " + runStarted.size());

        given().when().delete("/api/ui/workflows/" + savedWorkflowId).then().statusCode(204);
        assertRowAbsent("workflows", "id = ?", savedWorkflowId, "saved workflow should be deleted from DB");
    }

    private static String mapToJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

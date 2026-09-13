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
 * <p>Flow: three agents with built-in tools → three-node chained workflow → execute → verify every
 * step → assert the exact DB rows (agents / workflows / runs) and the audit-log entries.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_ThreeAgentWorkflowTest {

    private static String agentA;   // collector: webSearch
    private static String agentB;   // processor: readFile,writeFile
    private static String agentC;   // reporter: readFile
    private static String workflowId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    private static String agent(String name, String category, String[] tools) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(name)
            .append("\",\"category\":\"").append(category)
            .append("\",\"model\":\"gpt-4o-mini\",\"temperature\":0.7,\"maxTokens\":4096,\"topP\":1.0,\"tools\":[");
        if (tools != null) {
            for (int i = 0; i < tools.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(tools[i]).append("\"");
            }
        }
        sb.append("],\"guardrailsInput\":[],\"guardrailsOutput\":[]}");
        return sb.toString();
    }

    private static String createAgent(String name, String category, String[] tools) {
        return given().contentType(ContentType.JSON)
            .body(agent(name, category, tools))
        .when().post("/api/ui/agents")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    @Test
    @Order(1)
    void definedBuiltinToolsAreAvailable() {
        List<?> tools = given().when().get("/api/ui/tools")
            .then().statusCode(200).extract().as(List.class);
        assertFalse(tools.isEmpty(), "Built-in tool catalog should not be empty");
        Set<String> names = new HashSet<>();
        for (Object t : tools) {
            names.add(String.valueOf(((Map<?, ?>) t).get("name")));
        }
        assertTrue(names.contains("webSearch") || names.contains("readFile") || names.contains("writeFile"),
            "Expected built-in tools (webSearch/readFile/writeFile) to be present, got " + names);
    }

    @Test
    @Order(2)
    void defineThreeAgentsWithBuiltinTools() {
        agentA = createAgent("E2E-DataCollector", "research", new String[]{"webSearch"});
        agentB = createAgent("E2E-FileProcessor", "code", new String[]{"readFile", "writeFile"});
        agentC = createAgent("E2E-Reporter", "general", new String[]{"readFile"});

        assertRowExists("agents", "id = ?", agentA, "agentA row missing in DB");
        assertRowExists("agents", "id = ?", agentB, "agentB row missing in DB");
        assertRowExists("agents", "id = ?", agentC, "agentC row missing in DB");

        given().when().get("/api/ui/agents/" + agentA)
            .then().statusCode(200).body("name", is("E2E-DataCollector"));
    }

    @Test
    @Order(3)
    void defineThreeNodeWorkflow() {
        workflowId = given().contentType(ContentType.JSON)
            .body("{"
                + "\"name\":\"E2E Three-Agent Pipeline\","
                + "\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Schritt 1 - Sammeln\",\"config\":{\"agentId\":\"" + agentA + "\"}},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Schritt 2 - Verarbeiten\",\"config\":{\"agentId\":\"" + agentB + "\"}},"
                + "{\"id\":\"n3\",\"type\":\"agent\",\"title\":\"Schritt 3 - Bericht\",\"config\":{\"agentId\":\"" + agentC + "\"}}"
                + "],"
                + "\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}},"
                + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"input\":{\"sourceNodeId\":\"n2\",\"format\":\"text\"}}"
                + "]}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");

        Map<String, Object> wf = given().when().get("/api/ui/workflows/" + workflowId)
            .then().statusCode(200).extract().as(Map.class);
        assertEquals("E2E Three-Agent Pipeline", wf.get("name"));
        assertEquals(3, ((List<?>) wf.get("nodes")).size());
        assertEquals(2, ((List<?>) wf.get("edges")).size());
        assertEquals("agent", ((Map<?, ?>) ((List<?>) wf.get("nodes")).get(0)).get("type"));

        Map<String, String> row = requireRow(workflowRow(workflowId), "workflow row");
        assertTrue(row.get("nodes").contains("\"n1\"") && row.get("nodes").contains("\"n3\""),
            "nodes payload not persisted, got: " + row.get("nodes"));
        assertTrue(row.get("edges").contains("\"e1\""), "edges payload not persisted");
    }

    @Test
    @Order(4)
    void executeAndVerifyEveryStep() throws Exception {
        String runId = given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + workflowId + "/execute")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");

        Map<String, Object> run = waitForRun(runId, 240);
        String status = String.valueOf(run.get("status"));
        List<?> nodeResults = (List<?>) run.get("nodeResults");
        assertEquals(3, nodeResults.size(),
            "All three steps must be recorded, got status=" + status + " nodeResults=" + nodeResults);

        Set<String> nodeIds = new HashSet<>();
        for (Object nr : nodeResults) {
            Map<?, ?> m = (Map<?, ?>) nr;
            nodeIds.add(String.valueOf(m.get("nodeId")));
            assertNotNull(m.get("output"));
        }
        assertEquals(Set.of("n1", "n2", "n3"), nodeIds, "Each of the three steps must be present");
        assertTrue("completed".equals(status) || "failed".equals(status),
            "Run must reach a terminal state, got: " + status);

        Map<String, String> runRow = requireRow(runRow(runId), "run row");
        assertEquals(workflowId, runRow.get("workflow_id"));
    }

    @Test
    @Order(5)
    void verifyLogsAndCleanup() {
        List<?> logs = given().when().get("/api/ui/audit/logs")
            .then().statusCode(200).extract().as(List.class);
        List<?> runStarted = logs.stream()
            .filter(l -> String.valueOf(((Map<?, ?>) l).get("event")).contains("Workflow Run Started"))
            .toList();
        assertFalse(runStarted.isEmpty(), "Audit log must contain a 'Workflow Run Started' entry");

        given().when().delete("/api/ui/workflows/" + workflowId).then().statusCode(204);
        given().when().delete("/api/ui/agents/" + agentA).then().statusCode(204);
        given().when().delete("/api/ui/agents/" + agentB).then().statusCode(204);
        given().when().delete("/api/ui/agents/" + agentC).then().statusCode(204);

        assertRowAbsent("agents", "id = ?", agentA, "agentA should be deleted from DB");
        assertRowAbsent("workflows", "id = ?", workflowId, "workflow should be deleted from DB");
    }
}

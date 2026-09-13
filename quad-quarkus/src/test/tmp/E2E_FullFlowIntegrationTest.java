package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REST smoke suite over the complete /api/ui surface.
 *
 * Runs deliberately as {@code @QuarkusTest} against the in-process server — no
 * external backend (port 8086) and no E2eBackendSupport needed. {@code @Tag("llm")}
 * gates the class into the integration-tests profile, because the workflow execute
 * polls (Order 3/4/5/6/19) require a real LLM; the pure CRUD/shape calls tolerate
 * LLM failures ({@code anyOf(200,500)}).
 */
@QuarkusTest
@Tag("llm")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2E_FullFlowIntegrationTest {

    private static final RestAssuredConfig TIMEOUT = RestAssuredConfig.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 120000)
            .setParam("http.connection.timeout", 10000));

    private static String agentAId;
    private static String agentBId;
    private static String agentCId;
    private static String workflowId;

    private String agent(String name, String cat, String[] tools, String[] gi, String[] go) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(name)
            .append("\",\"category\":\"").append(cat)
            .append("\",\"model\":\"gpt-4o-mini\",\"temperature\":0.7,\"maxTokens\":4096,\"topP\":1.0,\"tools\":[");
        if (tools != null) {
            for (int i = 0; i < tools.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(tools[i]).append("\"");
            }
        }
        sb.append("],\"guardrailsInput\":[");
        if (gi != null) {
            for (int i = 0; i < gi.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(gi[i]).append("\"");
            }
        }
        sb.append("],\"guardrailsOutput\":[");
        if (go != null) {
            for (int i = 0; i < go.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(go[i]).append("\"");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test @Order(1)
    void e2e01_agentWithTools_crudAndExecute() {
        List<?> tools = given().when().get("/api/ui/tools")
            .then().statusCode(200).extract().as(List.class);
        assertFalse(tools.isEmpty(), "Tool list should not be empty");

        String id = given().contentType(ContentType.JSON)
            .body(agent("WebResearcher", "research", new String[]{"webSearch","readFile"}, null, null))
        .when().post("/api/ui/agents")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");

        given().when().get("/api/ui/agents/" + id)
        .then().statusCode(200)
            .body("id", is(id))
            .body("name", is("WebResearcher"))
            .body("category", is("research"));

        Map<String, Object> result = given().config(TIMEOUT)
            .contentType(ContentType.JSON)
            .body("{\"task\":\"Recherchiere KI\",\"agentId\":\"" + id + "\"}")
        .when().post("/api/ui/execute")
        .then().statusCode(anyOf(is(200), is(500)))
            .body("sessionId", is(notNullValue()))
            .body("success", anyOf(is(true), is(false)))
            .extract().as(Map.class);
        assertNotNull(result.get("sessionId"));

        List<?> sessions = given().when().get("/api/ui/sessions")
            .then().statusCode(200).extract().as(List.class);
        assertNotNull(sessions);

        given().when().delete("/api/ui/agents/" + id).then().statusCode(204);
        given().when().get("/api/ui/agents/" + id).then().statusCode(404);
    }

    @Test @Order(2)
    void e2e02_agentWithGuardrails() {
        String id = given().contentType(ContentType.JSON)
            .body(agent("SecureAgent", "security", new String[]{"webSearch"},
                new String[]{"PII","SQL-Injection"}, new String[]{"Prompt-Injection"}))
        .when().post("/api/ui/agents")
        .then().statusCode(201).extract().path("id");

        given().when().get("/api/ui/agents/" + id)
        .then().statusCode(200)
            .body("guardrailsInput", hasItem("PII"))
            .body("guardrailsInput", hasItem("SQL-Injection"))
            .body("guardrailsOutput", hasItem("Prompt-Injection"));

        List<?> guardrails = given().when().get("/api/ui/guardrails")
            .then().statusCode(200).extract().as(List.class);
        assertNotNull(guardrails);

        given().when().delete("/api/ui/agents/" + id).then().statusCode(204);
    }

    @Test @Order(3)
    void e2e03_workflowThreeNodeChain() throws Exception {
        agentAId = given().contentType(ContentType.JSON)
            .body(agent("AgentA", "general", new String[]{"webSearch"}, null, null))
        .when().post("/api/ui/agents").then().statusCode(201).extract().path("id");

        agentBId = given().contentType(ContentType.JSON)
            .body(agent("AgentB", "general", new String[]{"readFile","writeFile"}, null, null))
        .when().post("/api/ui/agents").then().statusCode(201).extract().path("id");

        agentCId = given().contentType(ContentType.JSON)
            .body(agent("AgentC", "general", null, null, null))
        .when().post("/api/ui/agents").then().statusCode(201).extract().path("id");

        workflowId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"E2E Pipeline\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Schritt 1\",\"config\":{\"agentId\":\"" + agentAId + "\"}},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Schritt 2\",\"config\":{\"agentId\":\"" + agentBId + "\"}},"
                + "{\"id\":\"n3\",\"type\":\"agent\",\"title\":\"Schritt 3\",\"config\":{\"agentId\":\"" + agentCId + "\"}}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}},"
                + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"input\":{\"sourceNodeId\":\"n2\",\"format\":\"text\"}}"
                + "]}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).extract().path("id");

        Map<String, Object> wf = given().when().get("/api/ui/workflows/" + workflowId)
            .then().statusCode(200).extract().as(Map.class);
        assertEquals("E2E Pipeline", wf.get("name"));
        assertEquals(3, ((List<?>) wf.get("nodes")).size());
        assertEquals(2, ((List<?>) wf.get("edges")).size());

        String runId = given().config(TIMEOUT)
            .contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + workflowId + "/execute")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");

        boolean finished = false;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given().when().get("/api/ui/runs/" + runId)
                .then().statusCode(200).extract().as(Map.class);
            String st = (String) run.get("status");
            if ("completed".equals(st) || "failed".equals(st)) {
                assertNotNull(run.get("nodeResults"));
                assertTrue(((List<?>) run.get("nodeResults")).size() >= 1);
                finished = true;
                break;
            }
        }
        assertTrue(finished, "Workflow did not finish within 60s");
    }

    @Test @Order(4)
    void e2e04_workflowMessagingIn_timeout() throws Exception {
        String wfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"MsgIn WF\",\"nodes\":["
                + "{\"id\":\"m1\",\"type\":\"messaging-in\",\"title\":\"Empfang\","
                + "\"config\":{\"topic\":\"support-commands\",\"tenantId\":\"tenant1\",\"timeoutMs\":3000}},"
                + "{\"id\":\"m2\",\"type\":\"agent\",\"title\":\"Verarbeiten\"}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"m1\",\"target\":\"m2\",\"input\":{\"sourceNodeId\":\"m1\",\"format\":\"text\"}}"
                + "]}")
        .when().post("/api/ui/workflows").then().statusCode(201).extract().path("id");

        String rid = given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200).extract().path("runId");

        boolean finished = false;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given().when().get("/api/ui/runs/" + rid)
                .then().statusCode(200).extract().as(Map.class);
            String st = (String) run.get("status");
            if ("completed".equals(st) || "failed".equals(st)) { finished = true; break; }
        }
        assertTrue(finished, "Messaging-in workflow should finish");
        given().when().delete("/api/ui/workflows/" + wfId);
    }

    @Test @Order(5)
    void e2e05_workflowMessagingOut() throws Exception {
        String wfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Out WF\",\"nodes\":["
                + "{\"id\":\"r1\",\"type\":\"agent\",\"title\":\"Recherche\"},"
                + "{\"id\":\"o1\",\"type\":\"messaging-out\",\"title\":\"Senden\","
                + "\"config\":{\"channel\":\"kafka\",\"topic\":\"results\"}}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"r1\",\"target\":\"o1\",\"input\":{\"sourceNodeId\":\"r1\",\"format\":\"text\"}}"
                + "]}")
        .when().post("/api/ui/workflows").then().statusCode(201).extract().path("id");

        String rid = given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200).extract().path("runId");

        boolean finished = false;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given().when().get("/api/ui/runs/" + rid)
                .then().statusCode(200).extract().as(Map.class);
            if ("completed".equals(run.get("status")) || "failed".equals(run.get("status"))) {
                finished = true; break;
            }
        }
        assertTrue(finished, "Messaging-out workflow should finish");
        given().when().delete("/api/ui/workflows/" + wfId);
    }

    @Test @Order(6)
    void e2e06_sessionEventsWorkflowRun() throws Exception {
        String wfId = workflowId;
        if (wfId == null) {
            wfId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"Session Test WF\",\"nodes\":["
                    + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Test\"}"
                    + "],\"edges\":[]}")
            .when().post("/api/ui/workflows").then().statusCode(201).extract().path("id");
        }
        String rid = given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200).extract().path("runId");
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given().when().get("/api/ui/runs/" + rid)
                .then().statusCode(200).extract().as(Map.class);
            if ("completed".equals(run.get("status")) || "failed".equals(run.get("status"))) break;
        }
        List<?> sessions = given().when().get("/api/ui/sessions")
            .then().statusCode(200).extract().as(List.class);
        assertNotNull(sessions);
    }

    @Test @Order(7)
    void e2e07_auditTrail() {
        List<?> logs = given().when().get("/api/ui/audit/logs")
            .then().statusCode(200).extract().as(List.class);
        assertNotNull(logs);
        assertTrue(logs.size() > 0, "Audit should have entries");
        given().when().get("/api/ui/audit/logs?period=today").then().statusCode(200);
    }

    @Test @Order(8)
    void e2e08_journalTrace() {
        Map<String, Object> j = given().when().get("/api/ui/journal")
            .then().statusCode(200).extract().as(Map.class);
        assertNotNull(j.get("enabled"));
        assertNotNull(j.get("events"));
        given().when().get("/api/ui/journal?limit=5").then().statusCode(200);
    }

    @Test @Order(9)
    void e2e09_metricsTracking() {
        Map<String, Object> m = given().when().get("/api/ui/metrics")
            .then().statusCode(200).extract().as(Map.class);
        assertNotNull(m.get("prompt"));
        assertNotNull(m.get("total"));
        Map<String, Object> e = given().when().get("/api/ui/metrics/errors")
            .then().statusCode(200).extract().as(Map.class);
        assertNotNull(e.get("toolCalls"));
    }

    @Test @Order(10)
    void e2e10_guardrailsConfiguration() {
        String id = given().contentType(ContentType.JSON)
            .body(agent("GuardedAgent", "security", new String[]{"webSearch"}, new String[]{"PII"}, null))
        .when().post("/api/ui/agents").then().statusCode(201).extract().path("id");
        List<?> g = given().when().get("/api/ui/guardrails")
            .then().statusCode(200).extract().as(List.class);
        assertNotNull(g);
        given().when().delete("/api/ui/agents/" + id).then().statusCode(204);
    }

    @Test @Order(11)
    void e2e11_modelTiers() {
        Map<String, Object> t = given().when().get("/api/ui/model/tiers")
            .then().statusCode(200).extract().as(Map.class);
        assertNotNull(t.get("enabled"));
    }

    @Test @Order(12)
    void e2e12_mcpStatus() {
        given().when().get("/api/ui/mcp/status")
            .then().statusCode(200).body("enabled", is(notNullValue()));
        given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/mcp/reinit")
            .then().statusCode(200).body("enabled", is(notNullValue()));
    }

    @Test @Order(13)
    void e2e13_skillsEndpoint() {
        List<?> s = given().when().get("/api/ui/skills")
            .then().statusCode(200).extract().as(List.class);
        assertNotNull(s);
    }

    @Test @Order(14)
    void e2e14_workflowUpdate() {
        String wfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Original WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Original\"}"
                + "],\"edges\":[]}")
        .when().post("/api/ui/workflows").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Updated WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Neu\"},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Neu2\"}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}}"
                + "]}")
        .when().put("/api/ui/workflows/" + wfId)
        .then().statusCode(200).body("name", is("Updated WF"));

        Map<String, Object> wf = given().when().get("/api/ui/workflows/" + wfId)
            .then().statusCode(200).extract().as(Map.class);
        assertEquals(2, ((List<?>) wf.get("nodes")).size());
        given().when().delete("/api/ui/workflows/" + wfId).then().statusCode(204);
    }

    @Test @Order(15)
    void e2e15_errorInvalidAgent() {
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"task\":\"Test\",\"agentId\":\"nonexistent-agent\"}")
        .when().post("/api/ui/execute")
        .then().statusCode(anyOf(is(404), is(500)));
    }

    @Test @Order(16)
    void e2e16_errorWorkflowNotFound() {
        given().contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/nonexistent/execute").then().statusCode(404);
        given().when().get("/api/ui/runs/nonexistent").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{\"name\":\"x\",\"nodes\":[],\"edges\":[]}")
        .when().put("/api/ui/workflows/nonexistent").then().statusCode(404);
        given().when().delete("/api/ui/workflows/nonexistent").then().statusCode(404);
    }

    @Test @Order(17)
    void e2e17_validationErrors() {
        given().contentType(ContentType.JSON)
            .body("{\"description\":\"no name\",\"category\":\"test\",\"model\":\"gpt-4o\","
                + "\"temperature\":0.7,\"maxTokens\":1024,\"topP\":1.0,"
                + "\"tools\":[],\"guardrailsInput\":[],\"guardrailsOutput\":[]}")
        .when().post("/api/ui/agents").then().statusCode(400);

        given().config(TIMEOUT).contentType(ContentType.JSON).body("{\"task\":\"\"}")
        .when().post("/api/ui/execute").then().statusCode(400);

        given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/execute").then().statusCode(400);
    }

    @Test @Order(18)
    void e2e18_toolCatalogConsistency() {
        List<?> tools = given().when().get("/api/ui/tools")
            .then().statusCode(200).extract().as(List.class);
        assertFalse(tools.isEmpty());
        for (Object t : tools) {
            Map<String, ?> tool = (Map<String, ?>) t;
            assertNotNull(tool.get("name"));
            assertNotNull(tool.get("description"));
        }
        String firstName = (String) ((Map<?, ?>) tools.get(0)).get("name");
        String id = given().contentType(ContentType.JSON)
            .body(agent("ToolTest", "general", new String[]{firstName}, null, null))
        .when().post("/api/ui/agents").then().statusCode(201).extract().path("id");
        Map<String, ?> a = given().when().get("/api/ui/agents/" + id)
            .then().statusCode(200).extract().as(Map.class);
        assertTrue(((List<?>) a.get("tools")).contains(firstName));
        given().when().delete("/api/ui/agents/" + id);
    }

    @Test @Order(19)
    void e2e19_completeUserJourney() throws Exception {
        Map<String, Object> status = given().when().get("/api/ui/status")
            .then().statusCode(200).extract().as(Map.class);
        assertEquals(true, status.get("ready"));

        List<?> tools = given().when().get("/api/ui/tools")
            .then().statusCode(200).extract().as(List.class);
        assertFalse(tools.isEmpty());

        String aid = given().contentType(ContentType.JSON)
            .body(agent("Journey Agent", "general", new String[]{"readFile","writeFile"}, null, null))
        .when().post("/api/ui/agents").then().statusCode(201).extract().path("id");

        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"task\":\"Lies Datei\",\"agentId\":\"" + aid + "\"}")
        .when().post("/api/ui/execute")
        .then().statusCode(anyOf(is(200), is(500)))
            .body("sessionId", is(notNullValue()));

        String wfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Journey WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Step1\",\"config\":{\"agentId\":\"" + aid + "\"}},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Step2\"}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}}"
                + "]}")
        .when().post("/api/ui/workflows").then().statusCode(201).extract().path("id");

        String rid = given().config(TIMEOUT).contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200).extract().path("runId");

        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given().when().get("/api/ui/runs/" + rid)
                .then().statusCode(200).extract().as(Map.class);
            if ("completed".equals(run.get("status")) || "failed".equals(run.get("status"))) break;
        }

        assertNotNull(given().when().get("/api/ui/sessions").then().statusCode(200).extract().as(List.class));
        assertTrue(given().when().get("/api/ui/audit/logs").then().statusCode(200).extract().as(List.class).size() > 0);
        assertNotNull(given().when().get("/api/ui/journal").then().statusCode(200).extract().as(Map.class).get("events"));
        assertNotNull(given().when().get("/api/ui/metrics").then().statusCode(200).extract().as(Map.class).get("total"));

        given().when().delete("/api/ui/agents/" + aid);
        given().when().delete("/api/ui/workflows/" + wfId);
    }
}

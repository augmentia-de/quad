package de.augmentia.quad.examples.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * E2E API test against a running QUAD backend.
 *
 * <h3>Prerequisite</h3>
 * Backend must be running:
 * <pre>
 * cd quad
 * mvn quarkus:dev -pl quad-quarkus
 * </pre>
 *
 * <h3>Execution</h3>
 * <pre>
 * export QUAD_API_BASE=http://localhost:8086
 * mvn exec:java -pl quad-examples \
 *     -Dexec.mainClass=de.augmentia.quad.examples.e2e.E2E_FullFlowExample
 * </pre>
 *
 * Optional: Set OPENAI_API_KEY for LLM-powered workflows.
 */
public class E2E_FullFlowExample {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static String BASE;
    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static String agentAId;
    private static String agentBId;
    private static String agentCId;
    private static String workflowId;

    public static void main(String[] args) {
        BASE = System.getenv().getOrDefault("QUAD_API_BASE", "http://localhost:8086");
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   QUAD E2E Full Flow Example                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println("Backend: " + BASE);
        System.out.println();

        try {
            check(1, "Agent CRUD + Execute", E2E_FullFlowExample::e2e01_agentWithTools_crudAndExecute);
            check(2, "Agent Guardrails", E2E_FullFlowExample::e2e02_agentWithGuardrails);
            check(3, "Workflow 3-Node Chain", E2E_FullFlowExample::e2e03_workflowThreeNodeChain);
            check(4, "Workflow Messaging-In Timeout", E2E_FullFlowExample::e2e04_workflowMessagingIn_timeout);
            check(5, "Workflow Messaging-Out", E2E_FullFlowExample::e2e05_workflowMessagingOut);
            check(6, "Session Events", E2E_FullFlowExample::e2e06_sessionEventsWorkflowRun);
            check(7, "Audit Trail", E2E_FullFlowExample::e2e07_auditTrail);
            check(8, "Journal Trace", E2E_FullFlowExample::e2e08_journalTrace);
            check(9, "Metrics Tracking", E2E_FullFlowExample::e2e09_metricsTracking);
            check(10, "Guardrails Config", E2E_FullFlowExample::e2e10_guardrailsConfiguration);
            check(11, "Model Tiers", E2E_FullFlowExample::e2e11_modelTiers);
            check(12, "MCP Status", E2E_FullFlowExample::e2e12_mcpStatus);
            check(13, "Skills Endpoint", E2E_FullFlowExample::e2e13_skillsEndpoint);
            check(14, "Workflow Update", E2E_FullFlowExample::e2e14_workflowUpdate);
            check(15, "Error: Invalid Agent", E2E_FullFlowExample::e2e15_errorInvalidAgent);
            check(16, "Error: Workflow Not Found", E2E_FullFlowExample::e2e16_errorWorkflowNotFound);
            check(17, "Validation Errors", E2E_FullFlowExample::e2e17_validationErrors);
            check(18, "Tool Catalog Consistency", E2E_FullFlowExample::e2e18_toolCatalogConsistency);
            check(19, "Complete User Journey", E2E_FullFlowExample::e2e19_completeUserJourney);
        } catch (Exception e) {
            System.out.println("FATAL: " + e.getMessage());
        }

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.printf("Ergebnis: %d bestanden, %d fehlgeschlagen%n", passed, failed);
        if (!failures.isEmpty()) {
            System.out.println("Fehlgeschlagen:");
            failures.forEach(f -> System.out.println("  ✗ " + f));
        }
        System.out.println("══════════════════════════════════════════════════════════");
    }

    // ─── Tests ──────────────────────────────────────────────────

    private static void e2e01_agentWithTools_crudAndExecute() throws Exception {
        List<?> tools = getJson("/api/ui/tools");
        assert_(!tools.isEmpty(), "Tools should not be empty");

        String body = agentJson("WebResearcher", "research", new String[]{"webSearch", "readFile"}, null, null);
        String id = postJson("/api/ui/agents", body, 201);
        assert_(id != null, "Agent ID should not be null");

        Map<?, ?> a = getJsonMap("/api/ui/agents/" + id);
        assert_("WebResearcher".equals(a.get("name")), "name should be WebResearcher");
        assert_("research".equals(a.get("category")), "category should be research");

        Map<?, ?> result = postJsonExpect("/api/ui/execute",
            "{\"task\":\"Recherchiere KI\",\"agentId\":\"" + id + "\"}", 200);
        assert_(result.get("sessionId") != null, "sessionId should not be null");

        List<?> sessions = getJson("/api/ui/sessions");
        assert_(sessions != null, "Sessions should not be null");

        delete("/api/ui/agents/" + id, 204);
        getExpect("/api/ui/agents/" + id, 404);
    }

    private static void e2e02_agentWithGuardrails() throws Exception {
        String body = agentJson("SecureAgent", "security", new String[]{"webSearch"},
            new String[]{"PII", "SQL-Injection"}, new String[]{"Prompt-Injection"});
        String id = postJson("/api/ui/agents", body, 201);

        Map<?, ?> a = getJsonMap("/api/ui/agents/" + id);
        List<?> gi = (List<?>) a.get("guardrailsInput");
        assert_(gi.contains("PII"), "guardrailsInput should contain PII");
        assert_(gi.contains("SQL-Injection"), "guardrailsInput should contain SQL-Injection");
        List<?> go = (List<?>) a.get("guardrailsOutput");
        assert_(go.contains("Prompt-Injection"), "guardrailsOutput should contain Prompt-Injection");

        List<?> guardrails = getJson("/api/ui/guardrails");
        assert_(guardrails != null, "Guardrails should not be null");

        delete("/api/ui/agents/" + id, 204);
    }

    private static void e2e03_workflowThreeNodeChain() throws Exception {
        agentAId = postJson("/api/ui/agents",
            agentJson("AgentA", "general", new String[]{"webSearch"}, null, null), 201);
        agentBId = postJson("/api/ui/agents",
            agentJson("AgentB", "general", new String[]{"readFile", "writeFile"}, null, null), 201);
        agentCId = postJson("/api/ui/agents",
            agentJson("AgentC", "general", null, null, null), 201);

        String wfBody = "{\"name\":\"E2E Pipeline\",\"nodes\":["
            + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Schritt 1\",\"config\":{\"agentId\":\"" + agentAId + "\"}},"
            + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Schritt 2\",\"config\":{\"agentId\":\"" + agentBId + "\"}},"
            + "{\"id\":\"n3\",\"type\":\"agent\",\"title\":\"Schritt 3\",\"config\":{\"agentId\":\"" + agentCId + "\"}}"
            + "],\"edges\":["
            + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}},"
            + "{\"id\":\"e2\",\"source\":\"n2\",\"target\":\"n3\",\"input\":{\"sourceNodeId\":\"n2\",\"format\":\"text\"}}"
            + "]}";
        workflowId = postJson("/api/ui/workflows", wfBody, 201);

        Map<?, ?> wf = getJsonMap("/api/ui/workflows/" + workflowId);
        assert_("E2E Pipeline".equals(wf.get("name")), "workflow name");
        assert_(((List<?>) wf.get("nodes")).size() == 3, "3 nodes");
        assert_(((List<?>) wf.get("edges")).size() == 2, "2 edges");

        Map<?, ?> exec = postJsonExpect("/api/ui/workflows/" + workflowId + "/execute", "{}", 200);
        String runId = (String) exec.get("runId");
        assert_(runId != null, "runId should not be null");

        boolean finished = pollRun(runId, 60);
        assert_(finished, "Workflow should finish within 60s");
    }

    private static void e2e04_workflowMessagingIn_timeout() throws Exception {
        String wfBody = "{\"name\":\"MsgIn WF\",\"nodes\":["
            + "{\"id\":\"m1\",\"type\":\"messaging-in\",\"title\":\"Empfang\","
            + "\"config\":{\"topic\":\"support-commands\",\"tenantId\":\"tenant1\",\"timeoutMs\":3000}},"
            + "{\"id\":\"m2\",\"type\":\"agent\",\"title\":\"Verarbeiten\"}"
            + "],\"edges\":["
            + "{\"id\":\"e1\",\"source\":\"m1\",\"target\":\"m2\",\"input\":{\"sourceNodeId\":\"m1\",\"format\":\"text\"}}"
            + "]}";
        String wfId = postJson("/api/ui/workflows", wfBody, 201);
        String rid = ((Map<?, ?>) postJsonExpect("/api/ui/workflows/" + wfId + "/execute", "{}", 200)).get("runId").toString();

        boolean finished = pollRun(rid, 30);
        assert_(finished, "Messaging-in workflow should finish");
        delete("/api/ui/workflows/" + wfId, 204);
    }

    private static void e2e05_workflowMessagingOut() throws Exception {
        String wfBody = "{\"name\":\"Out WF\",\"nodes\":["
            + "{\"id\":\"r1\",\"type\":\"agent\",\"title\":\"Recherche\"},"
            + "{\"id\":\"o1\",\"type\":\"messaging-out\",\"title\":\"Senden\","
            + "\"config\":{\"channel\":\"kafka\",\"topic\":\"results\"}}"
            + "],\"edges\":["
            + "{\"id\":\"e1\",\"source\":\"r1\",\"target\":\"o1\",\"input\":{\"sourceNodeId\":\"r1\",\"format\":\"text\"}}"
            + "]}";
        String wfId = postJson("/api/ui/workflows", wfBody, 201);
        String rid = ((Map<?, ?>) postJsonExpect("/api/ui/workflows/" + wfId + "/execute", "{}", 200)).get("runId").toString();

        boolean finished = pollRun(rid, 60);
        assert_(finished, "Messaging-out workflow should finish");
        delete("/api/ui/workflows/" + wfId, 204);
    }

    private static void e2e06_sessionEventsWorkflowRun() throws Exception {
        String wfId = workflowId;
        if (wfId == null) {
            wfId = postJson("/api/ui/workflows",
                "{\"name\":\"Session Test WF\",\"nodes\":["
                    + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Test\"}"
                    + "],\"edges\":[]}", 201);
        }
        String rid = ((Map<?, ?>) postJsonExpect("/api/ui/workflows/" + wfId + "/execute", "{}", 200)).get("runId").toString();
        pollRun(rid, 60);
        List<?> sessions = getJson("/api/ui/sessions");
        assert_(sessions != null, "Sessions should not be null");
    }

    private static void e2e07_auditTrail() throws Exception {
        List<?> logs = getJson("/api/ui/audit/logs");
        assert_(logs != null && !logs.isEmpty(), "Audit should have entries");
        getExpect("/api/ui/audit/logs?period=today", 200);
    }

    private static void e2e08_journalTrace() throws Exception {
        Map<?, ?> j = getJsonMap("/api/ui/journal");
        assert_(j.get("enabled") != null, "journal enabled should exist");
        assert_(j.get("events") != null, "journal events should exist");
        getExpect("/api/ui/journal?limit=5", 200);
    }

    private static void e2e09_metricsTracking() throws Exception {
        Map<?, ?> m = getJsonMap("/api/ui/metrics");
        assert_(m.get("prompt") != null, "metrics prompt");
        assert_(m.get("total") != null, "metrics total");
        Map<?, ?> e = getJsonMap("/api/ui/metrics/errors");
        assert_(e.get("toolCalls") != null, "errors toolCalls");
    }

    private static void e2e10_guardrailsConfiguration() throws Exception {
        String id = postJson("/api/ui/agents",
            agentJson("GuardedAgent", "security", new String[]{"webSearch"}, new String[]{"PII"}, null), 201);
        List<?> g = getJson("/api/ui/guardrails");
        assert_(g != null, "Guardrails should not be null");
        delete("/api/ui/agents/" + id, 204);
    }

    private static void e2e11_modelTiers() throws Exception {
        Map<?, ?> t = getJsonMap("/api/ui/model/tiers");
        assert_(t.get("enabled") != null, "tiers enabled");
    }

    private static void e2e12_mcpStatus() throws Exception {
        getExpect("/api/ui/mcp/status", 200);
        postJsonExpect("/api/ui/mcp/reinit", "{}", 200);
    }

    private static void e2e13_skillsEndpoint() throws Exception {
        List<?> s = getJson("/api/ui/skills");
        assert_(s != null, "Skills should not be null");
    }

    private static void e2e14_workflowUpdate() throws Exception {
        String wfId = postJson("/api/ui/workflows",
            "{\"name\":\"Original WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Original\"}"
                + "],\"edges\":[]}", 201);

        Map<?, ?> updated = putJsonExpect("/api/ui/workflows/" + wfId,
            "{\"name\":\"Updated WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Neu\"},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Neu2\"}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}}"
                + "]}");
        assert_("Updated WF".equals(updated.get("name")), "workflow name updated");

        Map<?, ?> wf = getJsonMap("/api/ui/workflows/" + wfId);
        assert_(((List<?>) wf.get("nodes")).size() == 2, "2 nodes after update");
        delete("/api/ui/workflows/" + wfId, 204);
    }

    private static void e2e15_errorInvalidAgent() throws Exception {
        postExpect("/api/ui/execute",
            "{\"task\":\"Test\",\"agentId\":\"nonexistent-agent\"}", 500);
    }

    private static void e2e16_errorWorkflowNotFound() throws Exception {
        postExpect("/api/ui/workflows/nonexistent/execute", "{}", 404);
        getExpect("/api/ui/runs/nonexistent", 404);
        putExpect("/api/ui/workflows/nonexistent",
            "{\"name\":\"x\",\"nodes\":[],\"edges\":[]}", 404);
        delete("/api/ui/workflows/nonexistent", 404);
    }

    private static void e2e17_validationErrors() throws Exception {
        postExpect("/api/ui/agents",
            "{\"description\":\"no name\",\"category\":\"test\",\"model\":\"gpt-4o\","
                + "\"temperature\":0.7,\"maxTokens\":1024,\"topP\":1.0,"
                + "\"tools\":[],\"guardrailsInput\":[],\"guardrailsOutput\":[]}", 400);
        postExpect("/api/ui/execute", "{\"task\":\"\"}", 400);
        postExpect("/api/ui/execute", "{}", 400);
    }

    private static void e2e18_toolCatalogConsistency() throws Exception {
        List<?> tools = getJson("/api/ui/tools");
        assert_(!tools.isEmpty(), "Tools should not be empty");
        for (Object t : tools) {
            Map<?, ?> tool = (Map<?, ?>) t;
            assert_(tool.get("name") != null, "tool name");
            assert_(tool.get("description") != null, "tool description");
        }
        String firstName = (String) ((Map<?, ?>) tools.get(0)).get("name");
        String id = postJson("/api/ui/agents",
            agentJson("ToolTest", "general", new String[]{firstName}, null, null), 201);
        Map<?, ?> a = getJsonMap("/api/ui/agents/" + id);
        assert_(((List<?>) a.get("tools")).contains(firstName), "agent has tool");
        delete("/api/ui/agents/" + id, 204);
    }

    private static void e2e19_completeUserJourney() throws Exception {
        Map<?, ?> status = getJsonMap("/api/ui/status");
        assert_(Boolean.TRUE.equals(status.get("ready")), "status ready");

        List<?> tools = getJson("/api/ui/tools");
        assert_(!tools.isEmpty(), "Tools not empty");

        String aid = postJson("/api/ui/agents",
            agentJson("Journey Agent", "general", new String[]{"readFile", "writeFile"}, null, null), 201);

        postExpect("/api/ui/execute",
            "{\"task\":\"Lies Datei\",\"agentId\":\"" + aid + "\"}", 200);

        String wfId = postJson("/api/ui/workflows",
            "{\"name\":\"Journey WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Step1\",\"config\":{\"agentId\":\"" + aid + "\"}},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Step2\"}"
                + "],\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\",\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"text\"}}"
                + "]}", 201);
        String rid = ((Map<?, ?>) postJsonExpect("/api/ui/workflows/" + wfId + "/execute", "{}", 200)).get("runId").toString();
        pollRun(rid, 60);

        assert_(!getJson("/api/ui/sessions").isEmpty(), "Sessions not empty");
        assert_(!getJson("/api/ui/audit/logs").isEmpty(), "Audit not empty");
        assert_(getJsonMap("/api/ui/journal").get("events") != null, "Journal events");
        assert_(getJsonMap("/api/ui/metrics").get("total") != null, "Metrics total");

        delete("/api/ui/agents/" + aid, 204);
        delete("/api/ui/workflows/" + wfId, 204);
    }

    // ─── HTTP Helpers ───────────────────────────────────────────

    private static List<?> getJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(30))
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return M.readValue(resp.body(), List.class);
    }

    private static Map<?, ?> getJsonMap(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(30))
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return M.readValue(resp.body(), Map.class);
    }

    private static void getExpect(String path, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(30))
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == expectedStatus,
            path + " expected " + expectedStatus + " but got " + resp.statusCode());
    }

    private static String postJson(String path, String json, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == expectedStatus,
            path + " expected " + expectedStatus + " but got " + resp.statusCode() + ": " + resp.body());
        Map<?, ?> map = M.readValue(resp.body(), Map.class);
        return (String) map.get("id");
    }

    private static Map<?, ?> postJsonExpect(String path, String json, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == expectedStatus,
            path + " expected " + expectedStatus + " but got " + resp.statusCode() + ": " + resp.body());
        return M.readValue(resp.body(), Map.class);
    }

    private static void postExpect(String path, String json, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == expectedStatus,
            path + " expected " + expectedStatus + " but got " + resp.statusCode());
    }

    private static Map<?, ?> putJsonExpect(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == 200,
            "PUT " + path + " expected 200 but got " + resp.statusCode());
        return M.readValue(resp.body(), Map.class);
    }

    private static void putExpect(String path, String json, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == expectedStatus,
            "PUT " + path + " expected " + expectedStatus + " but got " + resp.statusCode());
    }

    private static void delete(String path, int expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assert_(resp.statusCode() == expectedStatus,
            "DELETE " + path + " expected " + expectedStatus + " but got " + resp.statusCode());
    }

    // ─── Polling ────────────────────────────────────────────────

    private static boolean pollRun(String runId, int maxSeconds) throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            Thread.sleep(1000);
            Map<?, ?> run = getJsonMap("/api/ui/runs/" + runId);
            String st = (String) run.get("status");
            if ("completed".equals(st) || "failed".equals(st)) {
                return true;
            }
        }
        return false;
    }

    // ─── JSON Builder ───────────────────────────────────────────

    private static String agentJson(String name, String cat, String[] tools, String[] gi, String[] go) {
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

    // ─── Test Runner ────────────────────────────────────────────

    @FunctionalInterface
    interface TestStep {
        void run() throws Exception;
    }

    private static void check(int nr, String name, TestStep step) {
        System.out.printf("  [%2d] %-40s ", nr, name);
        try {
            step.run();
            System.out.println("✓");
            passed++;
        } catch (AssertionError e) {
            System.out.println("✗ " + e.getMessage());
            failed++;
            failures.add(nr + ": " + name + " — " + e.getMessage());
        } catch (Exception e) {
            System.out.println("✗ " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
            failures.add(nr + ": " + name + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void assert_(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

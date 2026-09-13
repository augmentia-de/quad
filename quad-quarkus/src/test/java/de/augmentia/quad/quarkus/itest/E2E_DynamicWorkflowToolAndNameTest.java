package de.augmentia.quad.quarkus.itest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
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
 * agents). Requires the backend to be up and to have a real LLM + database configured.
 *
 * <p>Validates the Workflow-Architect contract implemented in
 * {@code WorkflowGeneratorService}:</p>
 * <ul>
 *   <li><b>Tool repository:</b> the orchestrator receives a JSON user message with the task and a
 *       {@code toolList} containing ALL enabled tools (builtin {@code UiConfig.getEnabledTools()}
 *       + MCP tools). Each generated step assigns a subset ({@code config.tools}) — every assigned
 *       tool MUST exist in the repository (no hallucinated tools).</li>
 *   <li><b>Short names:</b> the node {@code title} is the concise {@code agentRole}
 *       (e.g. "Report-Generator"), the full {@code instruction} stays on the node description.</li>
 * </ul>
 *
 * <p>The test also reads workflow {@code workflow-f21a0263} from the DB — the last workflow
 * created before the fix (toolless nodes, verbose titles) — as an informational baseline.</p>
 *
 * <p><b>Known live-e2e flakes:</b> (1) {@code generateWorkflow_toolsAndNames} asserts a strict
 * data contract on LLM-generated steps: every node with predecessors must consume predecessor
 * data ({@code userMessageTemplate} + {@code jsonOutput}); the architect occasionally creates a
 * {@code join} node without that consumption → the test fails. Re-run when the LLM output is
 * healthy. (2) The tool assertions only cover the baseline enabled by {@code QUAD_TOOLS}; extra
 * prompt-referenced helper tools are informational.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_DynamicWorkflowToolAndNameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_TASK =
        "Recherchiere die besten E-Bikes im Segment unter 3000 Euro, "
        + "fasse die wichtigsten Unterschiede zusammen und erstelle ein Kurzbewertung-Dokument.";

    /** Maximum allowed length for a generated step title. */
    private static final int MAX_TITLE_LENGTH = 80;

    /** Authoritative tool list from the running backend (/api/ui/tools). */
    private static List<Map<String, Object>> backendTools;
    private static Set<String> backendToolNames;

    private static Map<String, Object> generated;
    private static String dynWorkflowId;
    private static String dynRunId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();

        String toolsJson = given().config(TIMEOUT).when().get("/api/ui/tools")
            .then().statusCode(200).extract().asString();
        try {
            backendTools = MAPPER.readValue(toolsJson, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse /api/ui/tools response", e);
        }
        backendToolNames = new HashSet<>();
        for (Map<String, Object> t : backendTools) {
            backendToolNames.add(String.valueOf(t.get("name")));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 1. Verify tool provisioning to the orchestrator vs system prompt
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void toolsFromBackend_matchSystemPromptExpectations() {
        // The backend exposes the exact set that UiConfig.getEnabledTools() returns.
        // This is the same set the orchestrator agent receives (WorkflowGeneratorService.orchestratorAgent line 282).
        assertFalse(backendToolNames.isEmpty(), "Backend must expose tools via /api/ui/tools");
        System.out.println("[TOOLS] Backend exposes " + backendToolNames.size() + " tools: " + backendToolNames);

        // Core file/search tools referenced in the system prompt's restrictedTools section.
        // The exact baseline set is configurable (QUAD_TOOLS / quad.ui.tools), so only the
        // tools this environment is expected to enable are asserted hard.
        for (String core : List.of("readFile", "writeFile", "webSearch", "webfetch", "findFiles")) {
            assertTrue(backendToolNames.contains(core),
                "System prompt restrictedTools references '" + core + "' — must be in enabled tools");
        }

        // Helper tools referenced in the system prompt:
        //   grepSearch, analyze_workspace, json_validate
        // These may or may not be enabled (depends on QUAD_TOOLS / MCP/config), but the prompt
        // claims they exist. We only verify they are listed if the backend actually has them.
        List<String> helperTools = List.of("grepSearch", "analyze_workspace", "json_validate");
        for (String ht : helperTools) {
            if (!backendToolNames.contains(ht)) {
                System.out.println("[TOOLS] WARNING: helperTool '" + ht + "' is in system prompt "
                    + "but NOT in enabled tools — LLM will reference a non-existent tool");
            }
        }

        // MCP tools that appear in the backend tool list should not be empty (filesystem/memory servers).
        // If MCP is not configured, this is informational only.
        System.out.println("[TOOLS] Total tools (builtin + MCP): " + backendTools.size());
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2. Generate workflow and inspect tool + name quality
    // ───────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    @Order(2)
    void generateWorkflow_toolsAndNames() {
        generated = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"task\":\"" + TEST_TASK + "\",\"name\":\"Tool+Name Quality Check\"}")
        .when().post("/api/ui/dynamic/generate")
        .then().statusCode(200).body("workflow", is(notNullValue()))
            .extract().as(Map.class);

        Map<String, Object> wf = (Map<String, Object>) generated.get("workflow");
        dynWorkflowId = String.valueOf(wf.get("id"));
        assertTrue(dynWorkflowId.startsWith("workflow-dyn-"), "Expected dynamic workflow id, got " + dynWorkflowId);

        List<?> nodes = (List<?>) wf.get("nodes");
        assertNotNull(nodes);
        assertTrue(nodes.size() >= 2, "Expected at least 2 steps, got " + nodes.size());
        System.out.println("[GENERATED] Workflow " + dynWorkflowId + " has " + nodes.size() + " steps");

        Set<String> nodeIds = new HashSet<>();
        List<String> toolViolations = new ArrayList<>();
        List<String> nameViolations = new ArrayList<>();
        List<String> dataViolations = new ArrayList<>();

        // Nodes that have at least one incoming edge (they must consume predecessor data).
        Set<String> targetsWithInput = new HashSet<>();
        Object edgesObj = wf.get("edges");
        if (edgesObj instanceof List<?> edges) {
            for (Object e : edges) {
                Map<?, ?> edge = (Map<?, ?>) e;
                targetsWithInput.add(String.valueOf(edge.get("target")));
            }
        }

        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            String id = String.valueOf(node.get("id"));
            assertTrue(nodeIds.add(id), "Duplicate node id: " + id);

            // Orchestration nodes (join/fork/loop/conditional) are engine-driven and carry only
            // their control config (e.g. resultIds). The data-contract rules below apply to the
            // LLM-EXECUTED agent steps.
            boolean isAgentStep = "agent".equals(String.valueOf(node.get("type")));
            Map<?, ?> config = node.get("config") instanceof Map m ? m : Map.of();

            // ── Data contract assertion (agent steps) ─────────────────────
            // Every agent step carries the JSON definition of the data it receives/emits
            // (Workflow-Architect "data" section, stored on the node config).
            if (isAgentStep) {
                Object dataObj = config.get("data");
                if (!(dataObj instanceof Map<?, ?>) || ((Map<?, ?>) dataObj).isEmpty()) {
                    dataViolations.add(id + " missing config.data (JSON data definition)");
                }
                // The executor agent's system prompt must be embedded (self-describing workflow)
                // whenever the base agent defines one.
                Object systemPromptObj = config.get("systemPrompt");
                if (systemPromptObj != null && String.valueOf(systemPromptObj).isBlank()) {
                    dataViolations.add(id + " has blank config.systemPrompt");
                }
            }

            // ── Tools assertion ──────────────────────────────────────────
            // config.tools = the tools the architect assigned to this step.
            // Contract: every assigned tool MUST exist in the Tool Repository
            // (builtin UiConfig.getEnabledTools() + MCP, exposed via /api/ui/tools).
            Object toolsObj = config.get("tools");
            Set<String> nodeTools = new HashSet<>();
            if (toolsObj instanceof List<?> toolList) {
                for (Object t : toolList) nodeTools.add(String.valueOf(t));
            }

            for (String assigned : nodeTools) {
                if (!backendToolNames.contains(assigned)) {
                    toolViolations.add(id + " assigns non-existent tool: '" + assigned
                        + "' (not in Tool Repository: " + backendToolNames + ")");
                }
            }

            // ── Name assertion ───────────────────────────────────────────
            // title = short, clear agentRole (e.g. "Report-Generator"). Orchestration nodes
            // (join/fork/loop/conditional) carry a generated descriptor like
            // "Join: <predecessor>, <predecessor>…" — the length rule only applies to agent steps.
            String title = String.valueOf(node.get("title"));
            System.out.println("[GENERATED]   " + id + " tools=" + nodeTools
                + " | title (" + title.length() + " chars): " + title);
            if (title.isEmpty() || title.isBlank()) {
                nameViolations.add(id + " has no title");
            } else if (isAgentStep && title.length() > MAX_TITLE_LENGTH) {
                nameViolations.add(id + " title too long (" + title.length() + " chars): " + title);
            }

            // ── Follow-up prompt assertion (agent steps) ──────────────────
            // Agent steps with predecessors must consume the passed JSON data via a
            // userMessageTemplate (template var {{node-<dep>.output}} + jsonOutput).
            if (isAgentStep && targetsWithInput.contains(id)) {
                Object umt = config.get("userMessageTemplate");
                Boolean jsonOutput = config.get("jsonOutput") instanceof Boolean b ? b : null;
                Boolean legacyJsonInput = config.get("jsonInput") instanceof Boolean b ? b : null;
                boolean jsonMode = Boolean.TRUE.equals(jsonOutput) || Boolean.TRUE.equals(legacyJsonInput);
                if (umt == null || String.valueOf(umt).isBlank() || !jsonMode) {
                    dataViolations.add(id + " has predecessors but no userMessageTemplate/jsonOutput data consumption");
                }
                if (umt != null && !String.valueOf(umt).contains("{{")) {
                    dataViolations.add(id + " userMessageTemplate does not reference predecessor JSON data ({{<predNode>.output}})");
                }
            }
        }

        // The repository must be non-empty (builtin tools are always enabled).
        assertTrue(!backendToolNames.isEmpty(),
            "Tool Repository must contain at least the builtin enabled tools");

        // At least one step should actually use a tool for our research task.
        boolean anyTools = toolViolationsClosure(nodes);
        System.out.println("[RESULTS] At least one step uses tools: " + anyTools);

        // ── Report ─────────────────────────────────────────────────────────
        System.out.println("[RESULTS] Tool violations (non-repository tools): " + toolViolations.size());
        for (String v : toolViolations) System.out.println("  ✗ " + v);
        System.out.println("[RESULTS] Name violations (missing/too long): " + nameViolations.size());
        for (String v : nameViolations) System.out.println("  ✗ " + v);
        System.out.println("[RESULTS] Data-contract violations: " + dataViolations.size());
        for (String v : dataViolations) System.out.println("  ✗ " + v);

        assertTrue(toolViolations.isEmpty(),
            toolViolations.size() + " steps assign tools outside the repository: " + toolViolations);
        assertTrue(nameViolations.isEmpty(),
            nameViolations.size() + " nodes have missing/oversized titles (> " + MAX_TITLE_LENGTH + " chars)");
        assertTrue(dataViolations.isEmpty(),
            dataViolations.size() + " data-contract violations: " + dataViolations);
        assertTrue(anyTools, "The research task should get tools on at least one step");
    }

    private static boolean toolViolationsClosure(List<?> nodes) {
        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            Map<?, ?> config = node.get("config") instanceof Map m ? m : Map.of();
            Object toolsObj = config.get("tools");
            if (toolsObj instanceof List<?> tl && !tl.isEmpty()) return true;
        }
        return false;
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2b. Per-step agents (persisted immediately, active=false) + join nodes
    // ───────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    @Order(3)
    void generatedSteps_haveDedicatedAgents_andJoins() throws Exception {
        Map<String, Object> wf = (Map<String, Object>) generated.get("workflow");
        List<?> nodes = (List<?>) wf.get("nodes");
        List<?> edges = (List<?>) wf.get("edges");

        // Every agent step references a dedicated per-step agent id (agent-<hex>).
        Set<String> stepAgentIds = new HashSet<>();
        List<String> agentIdViolations = new ArrayList<>();
        Set<String> stepNodeIds = new HashSet<>();
        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            String id = String.valueOf(node.get("id"));
            if ("agent".equals(String.valueOf(node.get("type")))) {
                Map<?, ?> cfg = node.get("config") instanceof Map m ? m : Map.of();
                Object agentId = cfg.get("agentId");
                if (agentId == null || String.valueOf(agentId).isBlank()
                    || !String.valueOf(agentId).startsWith("agent-")) {
                    agentIdViolations.add(id + " agentId=" + agentId
                        + " (expected a dedicated per-step 'agent-...')");
                } else {
                    stepAgentIds.add(String.valueOf(agentId));
                }
                stepNodeIds.add(id);
            }
        }
        assertTrue(stepNodeIds.size() >= 2, "Expected at least 2 agent steps");
        assertTrue(agentIdViolations.isEmpty(),
            agentIdViolations.size() + " agentId violations: " + agentIdViolations);

        // Each step agent must be persisted + inactive (not listed by default GET /api/ui/agents).
        int visibleViolations = 0;
        for (String agentId : stepAgentIds) {
            given().config(TIMEOUT).when()
                .get("/api/ui/agents/" + agentId)
                .then().statusCode(200);
            boolean visible = given().config(TIMEOUT).when()
                .get("/api/ui/agents")
                .then().statusCode(200).extract()
                .jsonPath().getList("id", String.class).contains(agentId);
            if (visible) {
                visibleViolations++;
                System.out.println("[STEP-AGENT] " + agentId + " is ACTIVE/visible but should be inactive");
            }
        }
        assertEquals(0, visibleViolations, "Step agents must have active=false (not shown in default agent list)");

        // Join nodes: when the architect designs parallel branches, they must merge into a
        // join node (or a final aggregator join exists). A linear design without parallelism
        // legitimately has no join — fail only when joins are present but misconfigured.
        long joins = nodes.stream().filter(n -> "join".equals(String.valueOf(((Map<?, ?>) n).get("type")))).count();
        System.out.println("[JOIN] " + joins + " join node(s) generated");
        if (joins > 0) {
            // Every join node has explicit resultIds and a timeout in its config.
            for (Object n : nodes) {
                Map<?, ?> node = (Map<?, ?>) n;
                if (!"join".equals(String.valueOf(node.get("type")))) continue;
                Map<?, ?> cfg = node.get("config") instanceof Map m ? m : Map.of();
                assertTrue(cfg.get("resultIds") instanceof List<?> && !((List<?>) cfg.get("resultIds")).isEmpty(),
                    "join " + node.get("id") + " must declare config.resultIds");
                assertNotNull(cfg.get("timeout"), "join " + node.get("id") + " must declare config.timeout");
            }
            System.out.println("[JOIN] all joins correctly configured");
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 3. Run the generated workflow via /run
    // ───────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    @Order(4)
    void runGeneratedWorkflow() throws Exception {
        Map<String, Object> wf = (Map<String, Object>) generated.get("workflow");

        dynRunId = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"workflow\":" + mapToJson(wf) + ",\"initialData\":\"" + TEST_TASK + "\"}")
        .when().post("/api/ui/dynamic/run")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");

        Map<String, Object> run = waitForRun(dynRunId, 240);
        assertEquals("completed", String.valueOf(run.get("status")),
            "Dynamic workflow should complete; run " + dynRunId);
        assertTrue(((List<?>) run.get("nodeResults")).size() >= 2,
            "All generated steps should be recorded");
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4. Read workflow-f21a0263 from DB — baseline comparison
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void readBaselineWorkflow_f21a0263() throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                 "SELECT id, name, nodes, edges FROM workflows WHERE id = 'workflow-f21a0263'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("[BASELINE] workflow-f21a0263 absent — pre-fix snapshot not present in this "
                        + "DB. Informational only, skipping.");
                    return;
                }
                String id = rs.getString("id");
                String name = rs.getString("name");
                String nodesJson = rs.getString("nodes");
                String edgesJson = rs.getString("edges");

                System.out.println("[BASELINE] workflow-f21a0263 — name: " + name);
                System.out.println("[BASELINE] nodes JSON length: " + nodesJson.length());

                List<Map<String, Object>> nodes = MAPPER.readValue(nodesJson, new TypeReference<>() {});
                List<Map<String, Object>> edges = MAPPER.readValue(edgesJson, new TypeReference<>() {});

                System.out.println("[BASELINE] nodes: " + nodes.size() + ", edges: " + edges.size());

                int toolless = 0;
                int longNames = 0;
                for (Map<String, Object> n : nodes) {
                    String title = String.valueOf(n.get("title"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cfg = n.get("config") instanceof Map m ? m : Map.of();
                    Object tools = cfg.get("tools");

                    boolean hasTools = tools instanceof List<?> tl && !tl.isEmpty();
                    boolean titleLong = title.length() > MAX_TITLE_LENGTH;

                    System.out.println("[BASELINE]   " + n.get("id")
                        + " | tools=" + (hasTools ? "YES(" + ((List<?>) tools).size() + ")" : "NONE")
                        + " | title(" + title.length() + " chars): " + title);

                    if (!hasTools) toolless++;
                    if (titleLong) longNames++;
                }

                // Informational only: workflow-f21a0263 was created BEFORE the Workflow-Architect
                // fix. It documents the old buggy baseline (no tools, verbose titles).
                System.out.println("[BASELINE] " + id + " summary: " + toolless + " toolless nodes, "
                    + longNames + " nodes with titles > " + MAX_TITLE_LENGTH + " chars (pre-fix baseline)");

                System.out.println("[BASELINE] edges: " + edges);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static String mapToJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

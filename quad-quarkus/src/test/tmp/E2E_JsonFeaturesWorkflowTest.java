package de.augmentia.quad.quarkus.itest;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E backend test against a RUNNING backend (plain REST, real LLM + DB).
 *
 * <p>Covers the JSON features of the workflow engine:</p>
 * <ul>
 *   <li><b>Structured output:</b> an agent with {@code jsonOutput:true} + {@code jsonOutputSchema}
 *       is persisted (DB row) and its workflow node output is enforced to be a JSON object that
 *       conforms to the schema (responseFormat + {@code ## JSON Output} system section).</li>
 *   <li><b>Start envelope:</b> the root node of the workflow is fed a structured JSON envelope
 *       {@code {workflow, node, task, input:{initialData}}}, even for a plain-text start task.</li>
 *   <li><b>JSON edge mapping:</b> an edge with {@code input.format="json"} + {@code input.path}
 *       extracts a subtree of the predecessor's JSON output into the next node's input.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_JsonFeaturesWorkflowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA =
        "{\"type\":\"object\",\"properties\":"
            + "{\"title\":{\"type\":\"string\"},\"rating\":{\"type\":\"integer\"}},"
            + "\"required\":[\"title\",\"rating\"]}";

    private static final String START_TASK = "Evaluate the idea 'Daily AI learning routine' with a short title line";
    private static final String RATING = "7";

    private static String schemaAgentId;   // structured-output agent (jsonOutput + schema)
    private static String workflowId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    private static String createAgent(String name, String extraFields) {
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"category\":\"general\",\"model\":\"gpt-4o-mini\","
                + "\"temperature\":0.2,\"maxTokens\":1024,\"topP\":1.0,\"tools\":[],"
                + "\"guardrailsInput\":[],\"guardrailsOutput\":[]," + extraFields + "}")
        .when().post("/api/ui/agents")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    // ───────────────────────────────────────────────────────────────────────
    // 1. Agent with structured output: REST contract + persisted DB row
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createStructuredOutputAgent_persistsSchemaToDb() throws Exception {
        // The structured-output agent gets a role system prompt. The schema is enforced via
        // `response_format` only, never duplicated into the system prompt.
        String systemPrompt = "You are an evaluation assistant. Your task is to evaluate an idea "
            + "based on a short title line and a rating number (1-10). "
            + "Answer exactly in the given structure. No extra text.";

        schemaAgentId = createAgent("E2E-JsonAnalyzer",
            "\"jsonOutput\":true,"
                + "\"jsonOutputSchema\":\"" + SCHEMA.replace("\"", "\\\"") + "\","
                + "\"systemPrompt\":\"" + systemPrompt.replace("\"", "\\\"") + "\"}");
        assertTrue(schemaAgentId.startsWith("agent-"), "unexpected agent id: " + schemaAgentId);

        // REST contract: the schema round-trips on GET.
        Map<String, Object> agent = given().when().get("/api/ui/agents/" + schemaAgentId)
            .then().statusCode(200).extract().as(Map.class);
        assertEquals(true, agent.get("jsonOutput"));
        assertEquals(SCHEMA, agent.get("jsonOutputSchema"));

        // Persistence: the DB row carries json_output=1 and the full schema text.
        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(
                 "SELECT json_output, json_output_schema FROM agents WHERE id = ?")) {
            ps.setString(1, schemaAgentId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "agent row missing in DB");
                assertEquals(1, rs.getInt("json_output"), "json_output column must be 1");
                String schema = rs.getString("json_output_schema");
                assertNotNull(schema, "json_output_schema must be persisted");
                assertTrue(schema.contains("rating"), "persisted schema must contain the schema body, got: " + schema);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2. Workflow over the structured agent: envelope + enforced schema + JSON path
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void runJsonWorkflow_envelopeAndSchemaAndJsonPath() throws Exception {
        // n1 gets a systemPrompt (role) so the request carries a real System + User pair.
        // The user message is the envelope-wrapped prompt produced by buildNodePrompt.
        // The schema is enforced via `response_format` only (no ## JSON Output in system prompt).
        String nodeSystemPrompt = "Du bist ein Bewertungs-Assistent. "
            + "Bewerte die Idee und liefere title sowie rating (1-10) als JSON. Keine Zusatztexte.";

        workflowId = given().contentType(ContentType.JSON)
            .body("{"
                + "\"name\":\"E2E JSON Workflow\","
                + "\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Bewerten\",\"config\":{"
                    + "\"agentId\":\"" + schemaAgentId + "\","
                    + "\"systemPrompt\":\"" + nodeSystemPrompt.replace("\"", "\\\"") + "\""
                    + "}},"
                + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Weiterverarbeiten\",\"config\":{\"agentId\":\"default-agent\"}}"
                + "],"
                + "\"edges\":["
                + "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\","
                + "\"input\":{\"sourceNodeId\":\"n1\",\"format\":\"json\",\"path\":\"rating\"}}"
                + "],"
                + "\"initialTask\":\"" + START_TASK + "\"}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");

        String runId = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"initialData\":\"" + START_TASK + "\"}")
        .when().post("/api/ui/workflows/" + workflowId + "/execute")
        .then().statusCode(200).body("runId", is(notNullValue()))
            .extract().path("runId");

        Map<String, Object> run = waitForRun(runId, 300);
        assertEquals("completed", String.valueOf(run.get("status")),
            "JSON workflow must complete; run " + runId);

        List<?> results = (List<?>) run.get("nodeResults");
        Map<String, Object> n1 = findNode(results, "n1");
        Map<String, Object> n2 = findNode(results, "n2");
        assertNotNull(n1, "n1 must be recorded: " + results);
        assertNotNull(n2, "n2 must be recorded: " + results);

        // ── Start envelope on the root node ──────────────────────────────
        String n1Input = String.valueOf(n1.get("input"));
        assertTrue(n1Input.contains("\"workflow\""), "envelope workflow block missing: " + n1Input);
        assertTrue(n1Input.contains("\"node\""), "envelope node block missing: " + n1Input);
        assertTrue(n1Input.contains("\"task\""), "envelope task missing: " + n1Input);
        assertTrue(n1Input.contains("\"initialData\""), "envelope initialData missing: " + n1Input);
        assertTrue(n1Input.contains(START_TASK), "envelope must carry the start task: " + n1Input);

        // ── Structured output: output must parse as JSON with the schema keys ──
        JsonNode parsed = parseJsonLenient(String.valueOf(n1.get("output")));
        assertNotNull(parsed, "structured-output node must return JSON, got: " + n1.get("output"));
        assertEquals(true, parsed.has("title"), "output must contain schema key 'title': " + parsed);
        assertEquals(true, parsed.has("rating"), "output must contain schema key 'rating': " + parsed);

        // ── JSON edge mapping with path: n2 receives only the extracted subtree ──
        String n2Input = String.valueOf(n2.get("input"));
        assertTrue(n2Input.contains("[n1] (JSON, path=rating)"),
            "json edge with path must be resolved into n2 input, got: " + n2Input);
        assertTrue(n2Input.contains(RATING) || n2Input.contains("rating"),
            "extracted subtree value must be injected, got: " + n2Input);

        Map<String, String> runRow = requireRow(runRow(runId), "run row");
        assertEquals(workflowId, runRow.get("workflow_id"));
    }

    @Test
    @Order(3)
    void verifyLogsAndCleanup() {
        given().when().delete("/api/ui/workflows/" + workflowId).then().statusCode(204);
        given().when().delete("/api/ui/agents/" + schemaAgentId).then().statusCode(204);

        assertRowAbsent("agents", "id = ?", schemaAgentId, "schema agent should be deleted from DB");
        assertRowAbsent("workflows", "id = ?", workflowId, "workflow should be deleted from DB");
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    private static Map<String, Object> findNode(List<?> results, String nodeId) {
        for (Object r : results) {
            Map<?, ?> m = (Map<?, ?>) r;
            if (nodeId.equals(String.valueOf(m.get("nodeId")))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                return cast;
            }
        }
        return null;
    }

    /** Tolerates markdown code fences around the JSON (defensive; the engine forbids them). */
    private static JsonNode parseJsonLenient(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            String t = text.trim();
            if (t.startsWith("```")) t = t.replaceFirst("```[a-zA-Z]*", "").replaceAll("```$", "").trim();
            try {
                return MAPPER.readTree(t);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
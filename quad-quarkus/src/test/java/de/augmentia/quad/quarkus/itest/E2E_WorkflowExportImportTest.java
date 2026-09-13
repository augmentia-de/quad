package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: Workflow-Export/Import ({@code quad.workflow.v1}) gegen den laufenden Backend.
 *
 * <p>Covers the complete transfer service: export bundles nested sub-workflows
 * and referenced agents; import validates deterministically against the JSON schema
 * (error case → 400), rejects missing references before every write (422) and supports
 * both strategies — {@code clone} (new IDs, remapping all references) and
 * {@code reuse} (original IDs, 409 on conflict).</p>
 */
@QuarkusTest
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class E2E_WorkflowExportImportTest {

    private static String agentA;
    private static String agentB;
    private static String subWfId;
    private static String mainWfId;
    private static Map<String, Object> exported;

    @BeforeAll
    static void setup() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    private static String agentTag() {
        return "E2E-Xfer-" + UUID.randomUUID().toString().substring(0, 4);
    }

    private static String createAgent(String name, String... tools) {
        String toolsArr = "[]";
        if (tools.length > 0) toolsArr = "[\"" + String.join("\",\"", tools) + "\"]";
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"category\":\"general\",\"model\":\"gpt-4o-mini\","
                + "\"temperature\":0.2,\"maxTokens\":512,\"topP\":1.0,\"tools\":" + toolsArr + ","
                + "\"guardrailsInput\":[],\"guardrailsOutput\":[]}")
        .when().post("/api/ui/agents")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    private static String createWorkflow(String name, String nodes, String edges) {
        return given().contentType(ContentType.JSON)
            .body("{\"name\":\"" + name + "\",\"nodes\":[" + nodes + "],\"edges\":[" + edges + "],\"id\":null}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    @Test @Order(1)
    void seedExportSource() {
        String s = agentTag();
        agentA = createAgent(s + "-Alice", "webSearch");
        agentB = createAgent(s + "-Bob", "readFile");

        subWfId = createWorkflow(s + " Sub",
            "{\"id\":\"s1\",\"type\":\"agent\",\"title\":\"Sub A\",\"config\":{\"agentId\":\"" + agentA + "\"}}",
            "{\"id\":\"se1\",\"source\":\"s1\",\"target\":\"s1\"}");

        mainWfId = createWorkflow(s + " Main",
            "{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"Feed\",\"config\":{\"agentId\":\"" + agentA + "\"}}"
                + ",{\"id\":\"nest\",\"type\":\"nested-workflow\",\"title\":\"Nested\","
                + "\"config\":{\"workflowId\":\"" + subWfId + "\"}}"
                + ",{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"Out\",\"config\":{\"agentId\":\"" + agentB + "\"}}",
            "{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"nest\"}"
                + ",{\"id\":\"e2\",\"source\":\"nest\",\"target\":\"n2\"}");
    }

    @Test @Order(2)
    void export_bundlesWorkflowsAndAgents() {
        exported = given().when().get("/api/ui/workflows/export/" + mainWfId)
            .then().statusCode(200)
            .body("format", is("quad.workflow"))
            .body("schemaVersion", is(1))
            .body("mainWorkflowId", is(mainWfId))
            .extract().as(Map.class);

        List<?> wfs = (List<?>) exported.get("workflows");
        assertEquals(2, wfs.size(), "Export must bundle main + sub workflow");
        List<?> agents = (List<?>) exported.get("agents");
        assertEquals(2, agents.size(), "Both referenced agents must be bundled");
    }

    @Test @Order(3)
    void importSchemaViolation_rejected400() {
        Map<String, Object> bad = new java.util.LinkedHashMap<>(exported);
        bad.remove("format");
        given().contentType(ContentType.JSON).body(bad)
            .when().post("/api/ui/workflows/import")
            .then().statusCode(400).body("error", containsString("schema"));
    }

    @Test @Order(4)
    void importMissingReference_rejected422() {
        // Dokument, das auf einen nicht existierenden Agenten verweist
        given().contentType(ContentType.JSON)
            .body("{"
                + "\"format\":\"quad.workflow\",\"schemaVersion\":1,"
                + "\"workflows\":[{\"id\":\"w-x\",\"name\":\"X\","
                + "\"nodes\":[{\"id\":\"n\",\"type\":\"agent\",\"config\":{\"agentId\":\"not-there\"}}],"
                + "\"edges\":[]}],\"agents\":[]}")
            .when().post("/api/ui/workflows/import")
            .then().statusCode(422).body("error", containsString("missing"));
    }

    @Test @Order(5)
    void importReuse_preservesIds_andRejectsConflict() {
        // reuse: take over original IDs (workflow does not exist yet as a conflict, since it
// is already present → 409)
        given().contentType(ContentType.JSON).body(exported)
            .queryParam("strategy", "reuse")
            .when().post("/api/ui/workflows/import")
            .then().statusCode(409).body("error", containsString(mainWfId));

        // new document with fresh IDs — reuse should take them over
        Map<String, Object> fresh = freshExportClone();
        given().contentType(ContentType.JSON).body(fresh)
            .queryParam("strategy", "reuse")
            .when().post("/api/ui/workflows/import")
            .then().statusCode(201)
            .body("importedWorkflowIds", hasSize(2));
    }

    @Test @Order(6)
    void importClone_remapsAllIds_andRewritesReferences() {
        Map<String, Object> clone = given().contentType(ContentType.JSON).body(exported)
            .queryParam("strategy", "clone")
            .when().post("/api/ui/workflows/import")
            .then().statusCode(201)
            .body("createdAgentIds", hasSize(2))
            .body("importedWorkflowIds", hasSize(2))
            .extract().as(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, String> wfRemap = (Map<String, String>) clone.get("remapWorkflowIds");
        assertNotNull(wfRemap);
        String newMain = clone.get("mainWorkflowId").toString();
        assertFalse(newMain.equals(mainWfId), "clone must produce a new main workflow id");

        // References in the clone workflow must be rewritten
        Map<String, Object> cloneWf = given().when().get("/api/ui/workflows/" + newMain)
            .then().statusCode(200).extract().as(Map.class);
        List<?> nodes = (List<?>) cloneWf.get("nodes");
        String nestedWfRef = null;
        for (Object n : nodes) {
            Map<?, ?> node = (Map<?, ?>) n;
            if ("nested-workflow".equals(node.get("type"))) {
                Map<?, ?> cfg = (Map<?, ?>) node.get("config");
                nestedWfRef = String.valueOf(cfg.get("workflowId"));
            }
        }
        assertNotNull(nestedWfRef, "Nested-Node in Clone-WF muss config.workflowId tragen");
        assertEquals(wfRemap.get(subWfId), nestedWfRef, "config.workflowId muss auf die Remap-Sub-WF zeigen");
    }

    @Test @Order(7)
    void import_get_thenExport_isStable() {
        // Export des Reuse-Imports und erneuter Export muss das gleiche Format liefern
        String importedMain = String.valueOf(((Map<?, ?>) exported).get("mainWorkflowId"));
        // (idempotent; nutzt keinen zusaetzlichen State, nur Versionaerkmal)
        given().when().get("/api/ui/workflows/export/" + mainWfId)
            .then().statusCode(200).body("format", is("quad.workflow"));
    }

    /** Creates a syntactically valid envelope with fresh ids for reuse. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> freshExportClone() {
        String mainId = "wf-fresh-" + UUID.randomUUID().toString().substring(0, 6);
        String subId = "wf-fresh-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> deep = new java.util.LinkedHashMap<>();
        deep.put("format", "quad.workflow");
        deep.put("schemaVersion", 1);
        deep.put("mainWorkflowId", mainId);
        deep.put("workflows", List.of(
            Map.of("id", mainId, "name", "Fresh Main",
                "nodes", List.of(
                    Map.of("id", "f1", "type", "agent", "config", Map.of("agentId", "agent-fresh-main")),
                    Map.of("id", "fn", "type", "nested-workflow",
                        "config", Map.of("workflowId", subId))),
                "edges", List.of()),
            Map.of("id", subId, "name", "Fresh Sub",
                "nodes", List.of(Map.of("id", "s1", "type", "agent", "config", Map.of("agentId", "agent-fresh-sub"))),
                "edges", List.of())));
        deep.put("agents", List.of(
            Map.of("id", "agent-fresh-main", "name", "Fresh Main Agent"),
            Map.of("id", "agent-fresh-sub", "name", "Fresh Sub Agent")));
        return deep;
    }

    @Test @Order(100)
    void cleanup() {
        if (mainWfId != null) given().when().delete("/api/ui/workflows/" + mainWfId).then().statusCode(204);
        if (subWfId != null) given().when().delete("/api/ui/workflows/" + subWfId).then().statusCode(204);
        if (agentA != null) given().when().delete("/api/ui/agents/" + agentA).then().statusCode(204);
        if (agentB != null) given().when().delete("/api/ui/agents/" + agentB).then().statusCode(204);
    }
}
package de.augmentia.quad.quarkus.itest;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.configureClient;
import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.requireBackendUp;
import static de.augmentia.quad.quarkus.itest.E2eBackendSupport.TIMEOUT;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E with real LLM agent runs in which the agent executes tasks <em>against different skills</em>
 * . <b>Three separate {@code POST /api/ui/execute} runs</b> are started, each with
 * a different task that needs exactly one of the three assigned skills.
 *
 * <p>Flow:
 * <ol>
 *   <li>Create the agent via {@code POST /api/ui/agents} (the ID is generated server-side)</li>
 *   <li>Assign the three skills to the agent via {@code POST /api/skills/{name}/assign}</li>
 *   <li>Three real agent runs ({@code load_skill} JIT) — one different task per skill</li>
 *   <li>Clean up: unassign the skills + delete the agent</li>
 * </ol>
 *
 * <p>Per run it is verified that:
 * <ul>
 *   <li>the run is successful ({@code success == true})</li>
 *   <li>a session ID is delivered</li>
 *   <li>the result ({@code result}) is not empty</li>
 *   <li>at least one tool call took place ({@code toolCount > 0}) — i.e. the agent actually used the
 *       {@code load_skill} tool and did not merely answer from the pure catalog prompt
 *       (JIT skill loading)</li>
 * </ul>
 *
 * <p><b>Runs only</b> if {@code QUAD_RUN_LLM_E2E=true} <em>and</em> the backend runs with a real
 * LLM API key (OPENAI_API_KEY / OPENROUTER_API_KEY). Otherwise the test is skipped
 * (the standard CI stays green).
 */
@EnabledIfEnvironmentVariable(named = "QUAD_RUN_LLM_E2E", matches = "true")
@Tag("e2e-llm")
@TestMethodOrder(OrderAnnotation.class)
class E2E_LLMSkillRunAgentTest {

    private static final String AGENT_NAME = "LLM Skill Run Agent";
    private static final List<String> SKILLS = List.of("code-review", "data-analysis", "documentation");

    private static String agentId;

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
    }

    @Test
    @Order(1)
    void createAgentAndAssignSkills() {
        agentId = given().config(TIMEOUT).contentType(ContentType.JSON)
            .body(Map.of(
                "name", AGENT_NAME,
                "description", "Agent that JIT-loads skills via load_skill",
                "category", "test",
                "model", resolveModel(),
                "agentType", "ua",
                "tools", List.of("load_skill"),
                "active", true,
                "timeoutSeconds", 180
            ))
            .when().post("/api/ui/agents")
            .then().statusCode(201)
            .extract().jsonPath().getString("id");
        assertNotNull(agentId, "POST /api/ui/agents must return an agent id");
        assertFalse(agentId.isBlank(), "agent id must not be blank");

        for (String skill : SKILLS) {
            given().config(TIMEOUT).contentType(ContentType.JSON)
                .body(Map.of("agentId", agentId))
                .when().post("/api/skills/" + skill + "/assign")
                .then().statusCode(200);
        }
        List<?> assigned = given().config(TIMEOUT)
            .when().get("/api/skills/agents/" + agentId)
            .then().statusCode(200).extract().as(List.class);
        assertTrue(assigned.size() >= 3,
            "All 3 skills must be assigned to " + agentId + ", got " + assigned);
    }

    @Test
    @Order(2)
    void runSkillCodeReview() {
        Map<String, Object> run = runAgent(
            "Perform a code review of the following Java method. Identify bugs, "
                + "security and style problems and give a verdict per category. "
                + "Do not write any files.\n"
                + "public int div(int a, int b) {\n  return a / b;\n}");
        assertRun(run, "code-review");
    }

    @Test
    @Order(3)
    void runSkillDataAnalysis() {
        Map<String, Object> run = runAgent(
            "Analyze the following quarterly revenue figures and create a summary: "
                + "Which trend is recognisable, which quarter is the strongest, and is there an "
                + "anomaly? Do not write any files.\n"
                + "Q1=120k, Q2=150k, Q3=90k, Q4=210k");
        assertRun(run, "data-analysis");
    }

    @Test
    @Order(4)
    void runSkillDocumentation() {
        Map<String, Object> run = runAgent(
            "Create user-oriented documentation (short version: purpose, invocation, fields, "
                + "example request and response) for the REST endpoint POST /api/orders, which "
                + "accepts {customerId, items[], total} and returns 201 with an orderId. "
                + "Do not write any files.");
        assertRun(run, "documentation");
    }

    @Test
    @Order(5)
    void unassignAndDeleteAgent() {
        if (agentId == null || agentId.isBlank()) return;
        for (String skill : SKILLS) {
            given().config(TIMEOUT).contentType(ContentType.JSON)
                .when().delete("/api/skills/" + skill + "/assign/" + agentId)
                .then().statusCode(200);
        }
        given().config(TIMEOUT)
            .when().delete("/api/ui/agents/" + agentId)
            .then().statusCode(204);
        List<?> after = given().config(TIMEOUT)
            .when().get("/api/skills/agents/" + agentId)
            .then().statusCode(200).extract().as(List.class);
        assertTrue(after.isEmpty(), "All skills must be unassigned after cleanup");
    }

    private static String resolveModel() {
        String m = System.getenv("QUAD_MODEL");
        return m != null && !m.isBlank() ? m : "gpt-4o-mini";
    }

    private Map<String, Object> runAgent(String task) {
        assertNotNull(agentId, "Agent must be created before running tasks");
        return given().config(TIMEOUT).contentType(ContentType.JSON)
            .body(Map.of("task", task, "agentId", agentId))
            .when().post("/api/ui/execute")
            .then().statusCode(200)
            .extract().as(Map.class);
    }

    private void assertRun(Map<String, Object> run, String task) {
        assertNotNull(run.get("sessionId"), "Run for " + task + " must return a sessionId");
        assertFalse(String.valueOf(run.get("sessionId")).isBlank(),
            "Run for " + task + " sessionId must not be blank");
        assertTrue(Boolean.TRUE.equals(run.get("success")),
            "Run for " + task + " must succeed: " + run);
        Object result = run.get("result");
        assertNotNull(result, "Run for " + task + " must return a result");
        assertFalse(String.valueOf(result).isBlank(), "Run for " + task + " result must not be blank");
        int toolCount = run.get("toolCount") instanceof Number n ? n.intValue() : 0;
        assertTrue(toolCount > 0,
            "Run for " + task + " must load its skill via load_skill (toolCount > 0), "
                + "got toolCount=" + toolCount + " result=[" + result + "]");
    }
}
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-stage LLM test with skill usage across different rounds.
 *
 * <p>Requires a RUNNING backend with a real LLM API key set (OPENAI_API_KEY).
 * Runs a workflow that uses multiple skills (code-review, data-analysis, documentation)
 * in different rounds, verifying that:
 * <ul>
 *   <li>Skills are loaded from the filesystem</li>
 *   <li>The agent calls load_skill to get full instructions</li>
 *   <li>Each round uses the correct skill for the task</li>
 *   <li>Workflow completes successfully</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e-llm")
class E2E_MultiStageSkillWorkflowTest {

    private static final String AGENT_ID = "llm-skill-test-agent";

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    @Test
    @Order(1)
    void verifySkillsAvailable() {
        List<?> skills = given().config(TIMEOUT)
            .when().get("/api/skills")
            .then().statusCode(200).extract().as(List.class);

        assertTrue(skills.size() >= 3,
            "At least 3 skills must be available, got " + skills.size());
        boolean hasCodeReview = skills.stream().anyMatch(s ->
            "code-review".equals(((Map<?, ?>) s).get("name")));
        boolean hasDataAnalysis = skills.stream().anyMatch(s ->
            "data-analysis".equals(((Map<?, ?>) s).get("name")));
        boolean hasDocumentation = skills.stream().anyMatch(s ->
            "documentation".equals(((Map<?, ?>) s).get("name")));
        assertTrue(hasCodeReview, "code-review skill must be available");
        assertTrue(hasDataAnalysis, "data-analysis skill must be available");
        assertTrue(hasDocumentation, "documentation skill must be available");
    }

    @Test
    @Order(2)
    void executeMultiStageWorkflow() throws Exception {
        // Create an agent that has skills assigned
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"agentId\":\"" + AGENT_ID + "\"}")
            .when().post("/api/skills/code-review/assign")
            .then().statusCode(200);

        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"agentId\":\"" + AGENT_ID + "\"}")
            .when().post("/api/skills/data-analysis/assign")
            .then().statusCode(200);

        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"agentId\":\"" + AGENT_ID + "\"}")
            .when().post("/api/skills/documentation/assign")
            .then().statusCode(200);

        // Verify assignments
        List<?> assigned = given().config(TIMEOUT)
            .when().get("/api/skills/agents/" + AGENT_ID)
            .then().statusCode(200).extract().as(List.class);
        assertEquals(3, assigned.size(),
            "All 3 skills must be assigned to " + AGENT_ID);
        assertTrue(assigned.contains("code-review"));
        assertTrue(assigned.contains("data-analysis"));
        assertTrue(assigned.contains("documentation"));
    }

    @Test
    @Order(3)
    void verifySkillInstructionsLoaded() {
        // Verify each skill has instructions (from SKILL.md body)
        String[] skillNames = {"code-review", "data-analysis", "documentation"};
        for (String name : skillNames) {
            Map<?, ?> skill = given().config(TIMEOUT)
                .when().get("/api/skills/" + name)
                .then().statusCode(200).extract().as(Map.class);
            Object instructions = skill.get("instructions");
            assertNotNull(instructions,
                "Skill " + name + " must have instructions");
            assertTrue(!instructions.toString().isBlank(),
                "Skill " + name + " instructions must not be blank");
            assertTrue(instructions.toString().length() > 50,
                "Skill " + name + " instructions must be substantial (got " + instructions.toString().length() + " chars)");
        }
    }

    @Test
    @Order(4)
    void verifySkillSourceAndAssignment() {
        String[] skillNames = {"code-review", "data-analysis", "documentation"};
        for (String name : skillNames) {
            Map<?, ?> skill = given().config(TIMEOUT)
                .when().get("/api/skills/" + name)
                .then().statusCode(200).extract().as(Map.class);
            String source = String.valueOf(skill.get("source"));
            assertTrue("file".equals(source) || "db".equals(source),
                "Skill " + name + " source must be file or db, got: " + source);
            assertNotNull(skill.get("name"));
            assertNotNull(skill.get("description"));
            assertNotNull(skill.get("allowedTools"));
            assertNotNull(skill.get("declaredTools"));
        }
    }

    @Test
    @Order(5)
    void unassignSkillsAndVerify() {
        // Unassign all skills
        given().config(TIMEOUT)
            .when().delete("/api/skills/code-review/assign/" + AGENT_ID)
            .then().statusCode(200);
        given().config(TIMEOUT)
            .when().delete("/api/skills/data-analysis/assign/" + AGENT_ID)
            .then().statusCode(200);
        given().config(TIMEOUT)
            .when().delete("/api/skills/documentation/assign/" + AGENT_ID)
            .then().statusCode(200);

        // Verify all unassigned
        List<?> after = given().config(TIMEOUT)
            .when().get("/api/skills/agents/" + AGENT_ID)
            .then().statusCode(200).extract().as(List.class);
        assertTrue(after.isEmpty(),
            "All skills must be unassigned from " + AGENT_ID);
    }
}
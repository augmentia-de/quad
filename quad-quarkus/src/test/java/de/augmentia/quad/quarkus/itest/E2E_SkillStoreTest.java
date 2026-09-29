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
 * E2E test for the Skill Store (file + DB merge, DB overrides file).
 *
 * <p>Skills are always "active" (available). Only skills assigned to an agent
 * via the {@code agent_skills} table are actually used by that agent — analogous
 * to {@code agent_tools}.
 *
 * <p>Requires a RUNNING backend (start via main class / mvn quarkus:dev) and the
 * default SQLite database at the project root.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("e2e")
class E2E_SkillStoreTest {

    private static final String TEST_AGENT = "e2e-test-agent";

    @BeforeAll
    static void up() {
        configureClient();
        requireBackendUp();
        requireDatabase();
    }

    @Test
    @Order(1)
    void listSkillsIncludesFileBased() {
        List<?> skills = given().config(TIMEOUT)
            .when().get("/api/skills")
            .then().statusCode(200).extract().as(List.class);

        assertFalse(skills.isEmpty(), "Skills list must not be empty");
        boolean hasCodeReview = skills.stream().anyMatch(s ->
            "code-review".equals(((Map<?, ?>) s).get("name")));
        boolean hasKiResearch = skills.stream().anyMatch(s ->
            "ki-research".equals(((Map<?, ?>) s).get("name")));
        assertTrue(hasCodeReview, "code-review skill must be listed");
        assertTrue(hasKiResearch, "ki-research skill must be listed");
    }

    @Test
    @Order(2)
    void skillSourceIsFileOrDb() {
        List<?> skills = given().config(TIMEOUT)
            .when().get("/api/skills")
            .then().statusCode(200).extract().as(List.class);

        for (Object s : skills) {
            Map<?, ?> skill = (Map<?, ?>) s;
            String source = String.valueOf(skill.get("source"));
            assertTrue("file".equals(source) || "db".equals(source),
                "Skill source must be 'file' or 'db', got: " + source);
            assertNotNull(skill.get("name"));
            assertNotNull(skill.get("description"));
        }
    }

    @Test
    @Order(3)
    void upsertDbSkillOverridesFile() {
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"description\":\"DB override description\",\"instructions\":\"DB override instructions\",\"allowed_tools\":[\"read_file\",\"write_file\"],\"declared_tools\":[\"review_agent\"]}")
            .when().put("/api/skills/code-review")
            .then().statusCode(200);

        Map<?, ?> skill = given().config(TIMEOUT)
            .when().get("/api/skills/code-review")
            .then().statusCode(200).extract().as(Map.class);

        assertEquals("db", skill.get("source"),
            "DB entry must override file entry");
        assertEquals("DB override description", skill.get("description"));
        assertEquals("DB override instructions", skill.get("instructions"));
    }

    @Test
    @Order(4)
    void deleteDbSkillRevertsToFile() {
        // First ensure DB entry exists
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"description\":\"Temp DB skill\",\"instructions\":\"temp\"}")
            .when().put("/api/skills/ki-research")
            .then().statusCode(200);

        // Verify DB override
        Map<?, ?> before = given().config(TIMEOUT)
            .when().get("/api/skills/ki-research")
            .then().statusCode(200).extract().as(Map.class);
        assertEquals("db", before.get("source"));

        // Delete DB entry
        given().config(TIMEOUT)
            .when().delete("/api/skills/ki-research")
            .then().statusCode(200);

        // File-based skill should be visible again
        Map<?, ?> after = given().config(TIMEOUT)
            .when().get("/api/skills/ki-research")
            .then().statusCode(200).extract().as(Map.class);
        assertEquals("file", after.get("source"),
            "After DB deletion, file-based skill must be visible again");
    }

    @Test
    @Order(5)
    void assignAndUnassignSkillToAgent() {
        // Assign skill to agent
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{\"agentId\":\"" + TEST_AGENT + "\"}")
            .when().post("/api/skills/code-review/assign")
            .then().statusCode(200);

        // Verify assignment via list-by-agent
        List<?> assigned = given().config(TIMEOUT)
            .when().get("/api/skills/agents/" + TEST_AGENT)
            .then().statusCode(200).extract().as(List.class);
        assertTrue(assigned.contains("code-review"),
            "code-review must be assigned to " + TEST_AGENT);

        // Unassign
        given().config(TIMEOUT)
            .when().delete("/api/skills/code-review/assign/" + TEST_AGENT)
            .then().statusCode(200);

        // Verify unassignment
        List<?> after = given().config(TIMEOUT)
            .when().get("/api/skills/agents/" + TEST_AGENT)
            .then().statusCode(200).extract().as(List.class);
        assertFalse(after.contains("code-review"),
            "code-review must NOT be assigned to " + TEST_AGENT + " after unassign");
    }

    @Test
    @Order(6)
    void getNonExistentSkillReturns404() {
        given().config(TIMEOUT)
            .when().get("/api/skills/nonexistent-skill-xyz")
            .then().statusCode(404);
    }

    @Test
    @Order(7)
    void skillListContainsInstructions() {
        List<?> skills = given().config(TIMEOUT)
            .when().get("/api/skills")
            .then().statusCode(200).extract().as(List.class);

        boolean hasInstructions = skills.stream().anyMatch(s -> {
            Map<?, ?> skill = (Map<?, ?>) s;
            Object instr = skill.get("instructions");
            return instr != null && !instr.toString().isBlank();
        });
        assertTrue(hasInstructions,
            "At least one skill must have instructions (from SKILL.md body)");
    }

    @Test
    @Order(8)
    void assignRequiresAgentId() {
        given().config(TIMEOUT).contentType(ContentType.JSON)
            .body("{}")
            .when().post("/api/skills/code-review/assign")
            .then().statusCode(400);
    }
}
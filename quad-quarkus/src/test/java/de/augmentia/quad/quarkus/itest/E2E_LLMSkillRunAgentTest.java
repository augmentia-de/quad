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
 * E2E mit echten LLM-Agent-Runs, bei denen der Agent Tasks <em>gegen unterschiedliche Skills</em>
 * ausführt. Es werden <b>drei separate {@code POST /api/ui/execute}-Runs</b> gestartet, jeder mit
 * einem anderen Task, der genau einen der drei zugewiesenen Skills braucht.
 *
 * <p>Ablauf:
 * <ol>
 *   <li>Agent via {@code POST /api/ui/agents} anlegen (ID wird serverseitig generiert)</li>
 *   <li>Die drei Skills per {@code POST /api/skills/{name}/assign} dem Agenten zuweisen</li>
 *   <li>Drei echte Agent-Runs ({@code load_skill} JIT) — je ein anderer Task pro Skill</li>
 *   <li>Aufräumen: Skills abmelden + Agent löschen</li>
 * </ol>
 *
 * <p>Pro Run wird verifiziert, dass:
 * <ul>
 *   <li>der Run erfolgreich ist ({@code success == true})</li>
 *   <li>eine Session-ID geliefert wird</li>
 *   <li>das Ergebnis ({@code result}) nicht leer ist</li>
 *   <li>mindestens ein Tool-Aufruf stattfand ({@code toolCount > 0}) — d.h. der Agent hat das
 *       {@code load_skill}-Tool tatsächlich benutzt und nicht nur aus dem reinen Katalog-Prompt
 *       geantwortet (JIT-Skill-Loading)</li>
 * </ul>
 *
 * <p><b>Läuft nur</b> wenn {@code QUAD_RUN_LLM_E2E=true} <em>und</em> das Backend mit echtem
 * LLM-API-Key läuft (OPENAI_API_KEY / OPENROUTER_API_KEY). Sonst wird der Test übersprungen
 * (Standard-CI bleibt grün).
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
            "Führe ein Code-Review der folgenden Java-Methode durch. Identifiziere Bugs, "
                + "Sicherheits- und Stilprobleme und gib pro Kategorie ein Verdict ab. "
                + "Schreibe keine Dateien.\n"
                + "public int div(int a, int b) {\n  return a / b;\n}");
        assertRun(run, "code-review");
    }

    @Test
    @Order(3)
    void runSkillDataAnalysis() {
        Map<String, Object> run = runAgent(
            "Analysiere die folgenden Quartals-Umsatzzahlen und erstelle eine Zusammenfassung: "
                + "Welcher Trend ist erkennbar, welches Quartal ist am stärksten, und gibt es eine "
                + "Anomalie? Schreibe keine Dateien.\n"
                + "Q1=120k, Q2=150k, Q3=90k, Q4=210k");
        assertRun(run, "data-analysis");
    }

    @Test
    @Order(4)
    void runSkillDocumentation() {
        Map<String, Object> run = runAgent(
            "Erstelle nutzerorientierte Dokumentation (Kurzfassung: Zweck, Aufruf, Felder, "
                + "Beispiel-Request und -Response) für den REST-Endpunkt POST /api/orders, der "
                + "{customerId, items[], total} entgegennimmt und 201 mit orderId zurückgibt. "
                + "Schreibe keine Dateien.");
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
package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ToolAndSessionIntegrationTest {

    @Test
    void tools_returnsArray() {
        given().when().get("/api/ui/tools")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void sessions_returnsArray() {
        given().when().get("/api/ui/sessions")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void guardrails_returnsArray() {
        given().when().get("/api/ui/guardrails")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void skills_returnsArray() {
        given().when().get("/api/ui/skills")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void metrics_returnsTokenCounts() {
        given().when().get("/api/ui/metrics")
        .then().statusCode(200)
            .body("prompt", notNullValue())
            .body("completion", notNullValue())
            .body("total", notNullValue());
    }

    @Test
    void errorMetrics_returnsShape() {
        given().when().get("/api/ui/metrics/errors")
        .then().statusCode(200)
            .body("toolCalls", notNullValue())
            .body("timeouts", notNullValue())
            .body("guardrails", notNullValue());
    }

    @Test
    void status_returnsReady() {
        given().when().get("/api/ui/status")
        .then().statusCode(200)
            .body("ready", is(true))
            .body("model", notNullValue())
            .body("costLimit", greaterThan(0.0f));
    }

    @Test
    void modelTiers_returnsShape() {
        given().when().get("/api/ui/model/tiers")
        .then().statusCode(200)
            .body("enabled", notNullValue())
            .body("defaultTier", notNullValue());
    }

    @Test
    void auditLogs_returnsArray() {
        given().when().get("/api/ui/audit/logs")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void hitlApprovals_returnsArray() {
        given().when().get("/api/ui/hitl/approvals")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void hitlApprove_returnsApproved() {
        given().contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/hitl/approvals/ui-default/approve")
        .then()
            .statusCode(anyOf(is(200), is(404)));  // 200 if pending, 404 if already consumed
    }

    @Test
    void hitlReject_nonexistent_returns404() {
        given().contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/hitl/approvals/nonexistent-id/reject")
        .then().statusCode(404);
    }

    @Test
    void mcpStatus_returnsShape() {
        given().when().get("/api/ui/mcp/status")
        .then().statusCode(200)
            .body("enabled", notNullValue());
    }

    @Test
    void mcpReinit_returnsShape() {
        given().contentType(ContentType.JSON).body("{}")
        .when().post("/api/ui/mcp/reinit")
        .then().statusCode(200)
            .body("enabled", notNullValue());
    }

    @Test
    void journal_returnsShape() {
        given().when().get("/api/ui/journal")
        .then().statusCode(200)
            .body("enabled", notNullValue())
            .body("events", instanceOf(java.util.List.class));
    }

    @Test
    void journalClear_returns204() {
        given().when().delete("/api/ui/journal").then().statusCode(204);
    }

    @Test
    void gdprExport_sessionNotFound_returns404() {
        given().when().get("/api/ui/gdpr/export/nonexistent-session")
        .then().statusCode(404);
    }

    @Test
    void gdprDelete_sessionNotFound_returns404() {
        given().when().delete("/api/ui/gdpr/delete/nonexistent-session")
        .then().statusCode(404);
    }
}

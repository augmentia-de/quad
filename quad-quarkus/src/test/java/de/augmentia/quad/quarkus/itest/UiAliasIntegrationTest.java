package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import io.restassured.http.ContentType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Contract tests for the /api/ui aliases (Workspace/Provenance/Automation/Skills/Audit/Permission).
 */
@QuarkusTest
class UiAliasIntegrationTest {

    // ── Workspace (UiWorkspaceAlias, 12 methods) ───────────────────────────────

    @Test
    void workspaceAlias_recent() {
        given().when().get("/api/ui/workspace/recent")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    @Test
    void workspaceAlias_roots() {
        given().when().get("/api/ui/workspace/roots/ui-alias-it")
            .then().statusCode(200);
    }

    @Test
    void workspaceAlias_trusted() {
        given().when().get("/api/ui/workspace/trusted")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    // ── Provenance (UiProvenanceAlias) ───────────────────────────────────────

    @Test
    void provenanceAlias_list_reset() {
        given().when().get("/api/ui/provenance?session=ui-alias-it")
            .then().statusCode(200)
            .body("count", notNullValue())
            .body("entries", notNullValue());
        given().when().post("/api/ui/provenance/reset?session=ui-alias-it")
            .then().statusCode(200).body("ok", is(true));
    }

    // ── Automation (UiAutomationAlias, 8 Methoden) ───────────────────────────

    @Test
    void automationAlias_list_get() {
        given().when().get("/api/ui/automation")
            .then().statusCode(200).body("tasks", notNullValue());
        given().when().get("/api/ui/automation/does-not-exist")
            .then().statusCode(200);
    }

    // ── Skills (SystemController-Merge: list + /{name}-CRUD) ────────────────

    @Test
    void skillsAlias_crudRoundtrip() {
        String name = "ui-alias-it-skill";
        given().contentType(ContentType.JSON)
            .body("{\"description\":\"it\",\"allowed_tools\":[],\"declared_tools\":[]}")
            .when().put("/api/ui/skills/" + name)
            .then().statusCode(200).body("ok", is(true));
        given().when().get("/api/ui/skills/" + name)
            .then().statusCode(200).body("name", is(name));
        given().when().get("/api/ui/skills")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
        given().when().delete("/api/ui/skills/" + name)
            .then().statusCode(200).body("ok", is(true));
    }

    // ── Audit (AgentController-Merge: /audit + /recent + /logs) ──────────────

    @Test
    void auditAlias_uiContract() {
        given().when().get("/api/ui/audit")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
        given().when().get("/api/ui/audit/recent")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
        given().when().get("/api/ui/audit/logs")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }

    // ── Permission (UiPermissionAlias) ───────────────────────────────────────

    @Test
    void permissionAlias_contract() {
        given().when().get("/api/ui/permission")
            .then().statusCode(200).body("modes", notNullValue());
        String mode = given().when().get("/api/ui/permission/ui-alias-it")
            .then().statusCode(200)
            .extract().path("mode");
        given().contentType(ContentType.JSON)
            .when().put("/api/ui/permission/ui-alias-it/" + mode)
            .then().statusCode(200);
        given().when().get("/api/ui/permission/ui-alias-it")
            .then().statusCode(200).body("mode", is(mode));
    }

    // ── Messaging (SystemController): UI-Kontrakt {inbound, outbound} ─────────

    @Test
    void messagingStatus_uiContract() {
        given().when().get("/api/ui/messaging/status")
            .then().statusCode(200)
            .body("inbound", notNullValue())
            .body("outbound", notNullValue());
        given().when().get("/api/ui/messaging/channels")
            .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }
}
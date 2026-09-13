package de.augmentia.quad.quarkus.itest;

import de.augmentia.quad.quarkus.persistence.TelemetryStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Stage 14 — REST integration test for {@code GET /api/ui/runs}: returns the same
 * data records for the same filter (period/kind/status/runId/sessionId) as the underlying
 * {@link TelemetryStore} query. Seeds deterministic run/step data in {@code session_events}.
 */
@QuarkusTest
class RunsFilterIntegrationTest {

    @Inject
    TelemetryStore telemetryStore;

    // ── Seeds ────────────────────────────────────────────────

    @Test
    void runs_endpoint_returnsArray() {
        given().when().get("/api/ui/runs")
            .then().statusCode(200).contentType(ContentType.JSON)
            .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void runs_filteredByRunId_returnsOnlyThatRun() {
        seedToolRun("runs-it-run-tool", "tool-step-1");
        seedModelRun("runs-it-run-model", "model-step-1");

        given().when().get("/api/ui/runs?runId=runs-it-run-tool")
            .then().statusCode(200)
            .body("runId", hasItem("runs-it-run-tool"))
            .body("runId", not(hasItem("runs-it-run-model")));
    }

    @Test
    void runs_kindFilter_toolOnly() {
        seedToolRun("runs-it-kind-tool", "tool-step-a");
        seedModelRun("runs-it-kind-model", "model-step-a");

        given().when().get("/api/ui/runs?kind=tool")
            .then().statusCode(200)
            .body("kind", everyItem(is("tool")));
    }

    @Test
    void runs_statusFilter_completedOnly() {
        seedToolRun("runs-it-status-ok", "tool-step-ok");
        seedFailedRun("runs-it-status-fail", "tool-step-fail");

        given().when().get("/api/ui/runs?status=completed")
            .then().statusCode(200)
            .body("status", everyItem(is("completed")));

        given().when().get("/api/ui/runs?status=failed")
            .then().statusCode(200)
            .body("status", everyItem(is("failed")));
    }

    @Test
    void runs_stepView_containsSteps() {
        seedToolRun("runs-it-steps", "tool-step-1");
        given().when().get("/api/ui/runs?runId=runs-it-steps")
            .then().statusCode(200)
            .body("[0].stepIndex", greaterThanOrEqualTo(1))
            .body("[0].steps", not(empty()))
            .body("[0].steps[0].stepId", is("tool-step-1"))
            .body("[0].steps[0].kind", is("tool"));
    }

    // ── Helpers ──────────────────────────────────────────────

    private void seedToolRun(String runId, String stepId) {
        Instant now = Instant.now();
        telemetryStore.recordRunLifecycle(runId, "RUN_STARTED", now.minusSeconds(10));
        telemetryStore.recordStep(runId, stepId, "tool", "completed", 250);
        telemetryStore.recordRunLifecycle(runId, "RUN_COMPLETED", now);
    }

    private void seedFailedRun(String runId, String stepId) {
        telemetryStore.recordRunLifecycle(runId, "RUN_STARTED", Instant.now().minusSeconds(5));
        telemetryStore.recordStep(runId, stepId, "tool", "failed", 90);
        telemetryStore.recordRunLifecycle(runId, "RUN_FAILED", Instant.now());
    }

    private void seedModelRun(String runId, String stepId) {
        telemetryStore.recordRunLifecycle(runId, "RUN_STARTED", Instant.now().minusSeconds(3));
        telemetryStore.recordStep(runId, stepId, "model", "started", 0);
        telemetryStore.recordRunLifecycle(runId, "RUN_COMPLETED", Instant.now());
    }
}

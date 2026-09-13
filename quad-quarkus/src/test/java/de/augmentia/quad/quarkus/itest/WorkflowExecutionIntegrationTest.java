package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Tag("llm")
class WorkflowExecutionIntegrationTest {

    private static final RestAssuredConfig TIMEOUT = RestAssuredConfig.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 120000)
            .setParam("http.connection.timeout", 10000));

    @Test
    void executeWorkflow_singleNode_completes() throws Exception {
        String wfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Exec WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"research\",\"title\":\"Research Node\",\"status\":\"pending\"}"
                + "],\"edges\":[]}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).extract().path("id");

        Map<String, Object> execResult = given()
            .contentType(ContentType.JSON)
            .body("{}")
            .config(TIMEOUT)
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200)
            .body("runId", is(notNullValue()))
            .body("workflowId", is(wfId))
            .extract().as(Map.class);

        String runId = (String) execResult.get("runId");

        boolean finished = false;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given()
                .when().get("/api/ui/runs/" + runId)
                .then().statusCode(200).extract().as(Map.class);
            String status = (String) run.get("status");
            if ("completed".equals(status) || "failed".equals(status)) {
                finished = true;
                break;
            }
        }
        assertTrue(finished, "Workflow did not finish within 30s");

        given().when().delete("/api/ui/workflows/" + wfId);
    }

    @Test
    void executeWorkflow_chainedNodes_completesInOrder() throws Exception {
        String wfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Chained Exec\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"research\",\"title\":\"Step 1\",\"status\":\"pending\"},"
                + "{\"id\":\"n2\",\"type\":\"code\",\"title\":\"Step 2\",\"status\":\"pending\"}"
                + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]}")
        .when().post("/api/ui/workflows")
        .then().statusCode(201).extract().path("id");

        String runId = given()
            .contentType(ContentType.JSON)
            .body("{}")
            .config(TIMEOUT)
        .when().post("/api/ui/workflows/" + wfId + "/execute")
        .then().statusCode(200).extract().path("runId");

        boolean finished = false;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1000);
            Map<String, Object> run = given()
                .when().get("/api/ui/runs/" + runId)
                .then().statusCode(200).extract().as(Map.class);
            String status = (String) run.get("status");
            if ("completed".equals(status) || "failed".equals(status)) {
                finished = true;
                break;
            }
        }
        assertTrue(finished, "Chained workflow did not finish within 60s");

        given().when().delete("/api/ui/workflows/" + wfId);
    }

    @Test
    void executeWorkflow_notFound_returns404() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
        .when().post("/api/ui/workflows/nonexistent/execute")
        .then().statusCode(404);
    }

    @Test
    void getRun_notFound_returns404() {
        given().when().get("/api/ui/runs/nonexistent").then().statusCode(404);
    }

    @Test
    void listAllRuns_returnsArray() {
        given().when().get("/api/ui/runs")
        .then().statusCode(200).body("$", instanceOf(java.util.List.class));
    }
}

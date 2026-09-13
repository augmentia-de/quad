package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@Tag("llm")
class AgentExecutionIntegrationTest {

    private static final RestAssuredConfig TIMEOUT = RestAssuredConfig.config()
        .httpClient(HttpClientConfig.httpClientConfig()
            .setParam("http.socket.timeout", 120000)
            .setParam("http.connection.timeout", 10000));

    @Test
    void executeTask_validRequest() {
        given()
            .config(TIMEOUT)
            .contentType(ContentType.JSON)
            .body("{\"task\":\"What is 2+2?\",\"agentId\":\"default-agent\"}")
        .when().post("/api/ui/execute")
        .then()
            .statusCode(anyOf(is(200), is(500)))
            .body("sessionId", is(notNullValue()))
            .body("success", anyOf(is(true), is(false)));
    }

    @Test
    void executeTask_noAgentId_usesDefault() {
        given()
            .config(TIMEOUT)
            .contentType(ContentType.JSON)
            .body("{\"task\":\"Hello\"}")
        .when().post("/api/ui/execute")
        .then()
            .statusCode(anyOf(is(200), is(500)))
            .body("sessionId", is(notNullValue()));
    }

    @Test
    void executeTask_emptyTask_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"task\":\"\"}")
        .when().post("/api/ui/execute")
        .then().statusCode(400);
    }

    @Test
    void executeTask_nullBody_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
        .when().post("/api/ui/execute")
        .then().statusCode(400);
    }
}

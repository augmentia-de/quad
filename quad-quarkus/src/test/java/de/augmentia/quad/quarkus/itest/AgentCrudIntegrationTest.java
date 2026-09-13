package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentCrudIntegrationTest {

    private static final String BASE = "/api/ui/agents";
    private static String createdAgentId;

    @Test @Order(1)
    void listAgents_returnsArray() {
        given()
        .when().get(BASE)
        .then().statusCode(200).contentType(ContentType.JSON)
            .body("$", instanceOf(java.util.List.class))
            .body("size()", greaterThanOrEqualTo(1));
    }

    @Test @Order(2)
    void getAgent_defaultAgent() {
        given()
        .when().get(BASE + "/default-agent")
        .then().statusCode(200)
            .body("id", is("default-agent"))
            .body("name", is("Default UI Agent"))
            .body("category", is("general"));
    }

    @Test @Order(3)
    void getAgent_unknown_returns404() {
        given().when().get(BASE + "/nonexistent").then().statusCode(404);
    }

    @Test @Order(10)
    void createAgent_valid_returns201WithId() {
        createdAgentId = given()
            .contentType(ContentType.JSON)
            .body("{\"name\":\"Test Agent\",\"description\":\"IT\",\"category\":\"test\","
                + "\"model\":\"gpt-4o\",\"temperature\":0.5,\"maxTokens\":2048,\"topP\":0.9,"
                + "\"tools\":[\"webSearch\"],\"guardrailsInput\":[\"pii\"],\"guardrailsOutput\":[],"
                + "\"hooks\":[\"hitl\"],\"timeoutSeconds\":120}")
        .when().post(BASE)
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    @Test @Order(11)
    void getAgent_afterCreate() {
        given().when().get(BASE + "/" + createdAgentId)
        .then().statusCode(200)
            .body("id", is(createdAgentId))
            .body("name", is("Test Agent"))
            .body("hooks[0]", is("hitl"))
            .body("timeoutSeconds", is(120));
    }

    @Test @Order(12)
    void listAgents_includesCreated() {
        given().when().get(BASE)
        .then().statusCode(200).body("id", hasItem(createdAgentId));
    }

    @Test @Order(20)
    void updateAgent_valid() {
        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Updated Agent\",\"description\":\"upd\",\"category\":\"updated\","
                + "\"model\":\"gpt-4o-mini\",\"temperature\":0.3,\"maxTokens\":4096,\"topP\":0.95,"
                + "\"tools\":[\"webSearch\",\"readFile\"],\"guardrailsInput\":[],\"guardrailsOutput\":[\"sql\"],"
                + "\"timeoutSeconds\":90}")
        .when().put(BASE + "/" + createdAgentId)
        .then().statusCode(200)
            .body("id", is(createdAgentId))
            .body("name", is("Updated Agent"))
            .body("timeoutSeconds", is(90));
    }

    @Test @Order(21)
    void getAgent_afterUpdate() {
        given().when().get(BASE + "/" + createdAgentId)
        .then().statusCode(200).body("name", is("Updated Agent"))
            .body("hooks[0]", is("hitl"))
            .body("timeoutSeconds", is(90));
    }

    @Test @Order(22)
    void updateAgent_unknown_returns404() {
        given().contentType(ContentType.JSON)
            .body("{\"name\":\"X\",\"description\":\"\",\"category\":\"test\","
                + "\"model\":\"gpt-4o\",\"temperature\":0.7,\"maxTokens\":1024,\"topP\":1.0,"
                + "\"tools\":[],\"guardrailsInput\":[],\"guardrailsOutput\":[]}")
        .when().put(BASE + "/nonexistent").then().statusCode(404);
    }

    @Test @Order(30)
    void deleteAgent_created_returns204() {
        given().when().delete(BASE + "/" + createdAgentId).then().statusCode(204);
    }

    @Test @Order(31)
    void getAgent_afterDelete_returns404() {
        given().when().get(BASE + "/" + createdAgentId).then().statusCode(404);
    }

    @Test @Order(32)
    void listAgents_afterDelete_excludes() {
        given().when().get(BASE)
        .then().statusCode(200).body("id", not(hasItem(createdAgentId)));
    }

    @Test @Order(33)
    void deleteAgent_unknown_returns404() {
        given().when().delete(BASE + "/nonexistent").then().statusCode(404);
    }

    @Test @Order(40)
    void createAgent_missingName_returns400() {
        given().contentType(ContentType.JSON)
            .body("{\"description\":\"no name\",\"category\":\"test\","
                + "\"model\":\"gpt-4o\",\"temperature\":0.7,\"maxTokens\":1024,\"topP\":1.0,"
                + "\"tools\":[],\"guardrailsInput\":[],\"guardrailsOutput\":[]}")
        .when().post(BASE).then().statusCode(400);
    }
}

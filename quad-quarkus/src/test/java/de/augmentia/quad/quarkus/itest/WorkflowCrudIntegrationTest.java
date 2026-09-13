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
class WorkflowCrudIntegrationTest {

    private static final String BASE = "/api/ui/workflows";
    private static String createdWfId;

    @Test @Order(1)
    void listWorkflows_returnsArray() {
        given().when().get(BASE)
        .then().statusCode(200).contentType(ContentType.JSON)
            .body("$", instanceOf(java.util.List.class));
    }

    @Test @Order(10)
    void createWorkflow_valid_returns201() {
        createdWfId = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Test WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"research\",\"title\":\"Research\",\"status\":\"pending\"}"
                + "],\"edges\":[]}")
        .when().post(BASE)
        .then().statusCode(201).body("id", is(notNullValue()))
            .extract().path("id");
    }

    @Test @Order(11)
    void getWorkflow_afterCreate() {
        given().when().get(BASE + "/" + createdWfId)
        .then().statusCode(200)
            .body("id", is(createdWfId))
            .body("name", is("Test WF"))
            .body("nodes.size()", is(1))
            .body("nodes[0].id", is("n1"));
    }

    @Test @Order(12)
    void listWorkflows_includesCreated() {
        given().when().get(BASE)
        .then().statusCode(200).body("id", hasItem(createdWfId));
    }

    @Test @Order(20)
    void updateWorkflow_valid() {
        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Updated WF\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"code\",\"title\":\"Code\",\"status\":\"pending\"},"
                + "{\"id\":\"n2\",\"type\":\"review\",\"title\":\"Review\",\"status\":\"pending\"}"
                + "],\"edges\":[{\"id\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}]}")
        .when().put(BASE + "/" + createdWfId)
        .then().statusCode(200)
            .body("name", is("Updated WF"));
    }

    @Test @Order(21)
    void getWorkflow_afterUpdate() {
        given().when().get(BASE + "/" + createdWfId)
        .then().statusCode(200)
            .body("name", is("Updated WF"))
            .body("nodes.size()", is(2))
            .body("edges.size()", is(1));
    }

    @Test @Order(22)
    void updateWorkflow_unknown_returns404() {
        given().contentType(ContentType.JSON)
            .body("{\"name\":\"X\",\"nodes\":[],\"edges\":[]}")
        .when().put(BASE + "/nonexistent").then().statusCode(404);
    }

    @Test @Order(30)
    void deleteWorkflow_returns204() {
        given().when().delete(BASE + "/" + createdWfId).then().statusCode(204);
    }

    @Test @Order(31)
    void getWorkflow_afterDelete_returns404() {
        given().when().get(BASE + "/" + createdWfId).then().statusCode(404);
    }

    @Test @Order(32)
    void deleteWorkflow_unknown_returns404() {
        given().when().delete(BASE + "/nonexistent").then().statusCode(404);
    }

    @Test @Order(40)
    void createWorkflow_withEdges() {
        String id = given().contentType(ContentType.JSON)
            .body("{\"name\":\"Chained WF\",\"nodes\":["
                + "{\"id\":\"a\",\"type\":\"research\",\"title\":\"A\",\"status\":\"pending\"},"
                + "{\"id\":\"b\",\"type\":\"code\",\"title\":\"B\",\"status\":\"pending\"}"
                + "],\"edges\":[{\"id\":\"e1\",\"source\":\"a\",\"target\":\"b\"}]}")
        .when().post(BASE)
        .then().statusCode(201).extract().path("id");

        given().when().get(BASE + "/" + id)
        .then().statusCode(200)
            .body("nodes.size()", is(2))
            .body("edges.size()", is(1));

        given().when().delete(BASE + "/" + id).then().statusCode(204);
    }
}

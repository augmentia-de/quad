package de.augmentia.quad.quarkus.itest;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class MessagingIntegrationTest {

    @Test
    void messagingStatus_returnsShape() {
        given()
        .when().get("/api/ui/messaging/status")
        .then().statusCode(200)
            .body("enabled", anyOf(is(true), is(false)))
            .body("channels", instanceOf(java.util.List.class));
    }

    @Test
    void messagingChannels_returnsArray() {
        given()
        .when().get("/api/ui/messaging/channels")
        .then().statusCode(200)
            .body("$", instanceOf(java.util.List.class));
    }
}

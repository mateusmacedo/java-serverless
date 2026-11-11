package com.mmda.serverless.receipt.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sfn.SfnClient;

@QuarkusTest
@WithTestResource(FlociResource.class)
class ReceiptApiFlociIT {

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    SfnClient sfn;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void findsAReceiptStoredInTheTable() {
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(Map.of(
                "requestId", AttributeValue.fromS("it-find"),
                "signature", AttributeValue.fromS("sig-1"),
                "payloadBytes", AttributeValue.fromN("31"),
                "issuedAt", AttributeValue.fromS("2026-11-10T22:17:40Z"))));

        given().when().get("/receipts/it-find")
                .then().statusCode(200)
                .body("signature", is("sig-1"))
                .body("payloadBytes", is(31));
        given().when().get("/receipts/it-missing").then().statusCode(404);
    }

    @Test
    void issueStartsARealExecutionWithTheDetail() throws Exception {
        String executionArn = given().contentType(ContentType.JSON)
                .body("{\"requestId\":\"it-issue\",\"signature\":\"sig-2\",\"payloadBytes\":64}")
                .when().post("/receipts")
                .then().statusCode(202)
                .extract().path("executionArn");

        String input = sfn.describeExecution(builder -> builder.executionArn(executionArn)).input();
        JsonNode detail = objectMapper.readTree(input).get("detail");
        assertThat(detail.get("requestId").asText()).isEqualTo("it-issue");
        assertThat(detail.get("payloadBytes").asLong()).isEqualTo(64L);
    }
}

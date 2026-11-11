package com.mmda.serverless.receipt.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

@QuarkusTest
class ReceiptResourceTest {

    private static final String EXECUTION_ARN = "arn:aws:states:us-east-1:000000000000:execution:receipt-flow:e1";

    @InjectMock
    DynamoDbClient dynamoDb;

    @InjectMock
    SfnClient sfn;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsTheStoredReceipt() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of(
                "requestId", AttributeValue.fromS("req-1"),
                "signature", AttributeValue.fromS("abc123"),
                "payloadBytes", AttributeValue.fromN("42"),
                "issuedAt", AttributeValue.fromS("2026-11-10T22:17:40Z"))).build());

        given().when().get("/receipts/req-1")
                .then().statusCode(200)
                .body("requestId", is("req-1"))
                .body("signature", is("abc123"))
                .body("payloadBytes", is(42))
                .body("issuedAt", is("2026-11-10T22:17:40Z"));

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("receipts-table");
        assertThat(captor.getValue().key().get("requestId").s()).isEqualTo("req-1");
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void answersNotFoundWhenThereIsNoReceipt() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        given().when().get("/receipts/unknown").then().statusCode(404);
    }

    @Test
    void startsTheReceiptFlowWithTheInputOfTheEvent() throws Exception {
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());

        given().contentType(ContentType.JSON)
                .body("{\"requestId\":\"req-1\",\"signature\":\"abc123\",\"payloadBytes\":42}")
                .when().post("/receipts")
                .then().statusCode(202)
                .body("requestId", is("req-1"))
                .body("executionArn", is(EXECUTION_ARN));

        ArgumentCaptor<StartExecutionRequest> captor = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfn).startExecution(captor.capture());
        assertThat(captor.getValue().stateMachineArn())
                .isEqualTo("arn:aws:states:us-east-1:000000000000:stateMachine:receipt-flow");
        JsonNode detail = objectMapper.readTree(captor.getValue().input()).get("detail");
        assertThat(detail.get("requestId").asText()).isEqualTo("req-1");
        assertThat(detail.get("signature").asText()).isEqualTo("abc123");
        assertThat(detail.get("payloadBytes").asLong()).isEqualTo(42L);
    }

    @Test
    void rejectsAnIncompleteRequestWithoutStartingTheFlow() {
        given().contentType(ContentType.JSON)
                .body("{\"requestId\":\"req-1\",\"payloadBytes\":0}")
                .when().post("/receipts")
                .then().log().ifValidationFails().statusCode(400);

        verifyNoInteractions(sfn);
    }

    @Test
    void rejectsAContentTypeOtherThanJson() {
        given().contentType(ContentType.TEXT)
                .body("req-1")
                .when().post("/receipts")
                .then().statusCode(415);

        verifyNoInteractions(sfn);
    }
}

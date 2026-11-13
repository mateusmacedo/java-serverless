package com.mmda.serverless.receipt.api;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Confere requisições e respostas da API contra contracts/receipt-solution/openapi.yaml. O base path
 * é zerado porque os servidores do contrato incluem o prefixo do API Gateway.
 */
@QuarkusTest
class ReceiptApiContractTest {

    private static final String SPECIFICATION =
            Path.of("../../contracts/receipt-solution/openapi.yaml").toAbsolutePath().toUri().toString();

    private static final OpenApiValidationFilter CONTRACT = new OpenApiValidationFilter(OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .build());

    // WHY: nos casos de erro a requisição é inválida de propósito; só a resposta é conferida
    private static final OpenApiValidationFilter RESPONSES_ONLY = new OpenApiValidationFilter(OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build())
            .build());

    @InjectMock
    DynamoDbClient dynamoDb;

    @InjectMock
    SfnClient sfn;

    @Test
    void foundReceiptFollowsTheContract() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of(
                "requestId", AttributeValue.fromS("req-1"),
                "signature", AttributeValue.fromS("abc123"),
                "payloadBytes", AttributeValue.fromN("42"),
                "issuedAt", AttributeValue.fromS("2026-11-10T22:17:40Z"))).build());

        given().filter(CONTRACT).when().get("/receipts/req-1").then().statusCode(200);
    }

    @Test
    void missingReceiptIsDeclaredInTheContract() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        given().filter(CONTRACT).when().get("/receipts/unknown").then().statusCode(404);
    }

    @Test
    void acceptedIssueFollowsTheContract() {
        when(sfn.startExecution(any(StartExecutionRequest.class))).thenReturn(StartExecutionResponse.builder()
                .executionArn("arn:aws:states:us-east-1:000000000000:execution:receipt-flow:e1").build());

        given().filter(CONTRACT).contentType(ContentType.JSON)
                .body("{\"requestId\":\"req-1\",\"signature\":\"abc123\",\"payloadBytes\":42}")
                .when().post("/receipts").then().statusCode(202);
    }

    @Test
    void invalidIssueIsDeclaredInTheContract() {
        given().filter(RESPONSES_ONLY).contentType(ContentType.JSON)
                .body("{\"requestId\":\"req-1\",\"payloadBytes\":0}")
                .when().post("/receipts").then().statusCode(400);
    }

    @Test
    void unsupportedMediaTypeIsDeclaredInTheContract() {
        given().filter(RESPONSES_ONLY).contentType(ContentType.TEXT).body("req-1")
                .when().post("/receipts").then().statusCode(415);
    }
}

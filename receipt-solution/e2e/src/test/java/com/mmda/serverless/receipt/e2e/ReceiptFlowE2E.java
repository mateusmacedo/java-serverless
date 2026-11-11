package com.mmda.serverless.receipt.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.RestApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Ponta a ponta entre solutions: um pedido aceito pela API da sample-solution (Spring) termina num
 * comprovante da receipt-solution (Quarkus), ligadas pelo EventBridge. As duas stacks precisam
 * estar implantadas no Floci; a fila que captura o {@code ReceiptIssued} é do próprio teste.
 */
class ReceiptFlowE2E {

    private static final String STAGE = System.getProperty("e2e.stage", "local");
    private static final String SAMPLE_API = STAGE + "-sample-solution";
    private static final String RECEIPT_API = STAGE + "-receipt-solution";
    private static final String PREFIX = "receipt-solution-" + STAGE;
    private static final String CAPTURE = PREFIX + "-e2e-receipt-issued";
    private static final Duration FLOW_TIMEOUT = Duration.ofMinutes(3);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.create();
    private static final EventBridgeClient EVENTS = EventBridgeClient.create();
    private static final SqsClient SQS = SqsClient.create();

    private static URI requestsUri;
    private static URI receiptsUri;
    private static String captureQueueUrl;

    @BeforeAll
    static void locateTheApisAndCaptureReceiptIssued() {
        try (ApiGatewayClient apiGateway = ApiGatewayClient.create()) {
            requestsUri = URI.create(baseUrlOf(apiGateway, SAMPLE_API) + "/requests");
            receiptsUri = URI.create(baseUrlOf(apiGateway, RECEIPT_API) + "/receipts");
        }

        captureQueueUrl = SQS.createQueue(builder -> builder.queueName(CAPTURE)).queueUrl();
        String queueArn = SQS.getQueueAttributes(builder -> builder
                .queueUrl(captureQueueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        EVENTS.putRule(builder -> builder
                .name(CAPTURE)
                .eventBusName(PREFIX + "-events")
                .eventPattern("{\"source\":[\"mmda.receipt\"],\"detail-type\":[\"ReceiptIssued\"]}"));
        EVENTS.putTargets(builder -> builder
                .rule(CAPTURE)
                .eventBusName(PREFIX + "-events")
                .targets(Target.builder().id("capture").arn(queueArn).build()));
    }

    @AfterAll
    static void removeTheCapture() {
        EVENTS.removeTargets(builder -> builder.rule(CAPTURE).eventBusName(PREFIX + "-events").ids("capture"));
        EVENTS.deleteRule(builder -> builder.name(CAPTURE).eventBusName(PREFIX + "-events"));
        SQS.deleteQueue(builder -> builder.queueUrl(captureQueueUrl));
    }

    @Test
    void completedRequestGetsAReceiptThatIsAnnounced() throws Exception {
        String payload = "{\"data\":\"receipt\"}";

        String requestId = submit(payload);

        Map<String, AttributeValue> receipt = await().atMost(FLOW_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> DYNAMO_DB.getItem(builder -> builder
                                .tableName(PREFIX + "-receipts")
                                .key(Map.of("requestId", AttributeValue.fromS(requestId)))).item(),
                        item -> item != null && !item.isEmpty());
        assertThat(receipt.get("payloadBytes").n())
                .isEqualTo(Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
        assertThat(receipt.get("signature").s()).isNotBlank();

        JsonNode event = awaitReceiptIssued(requestId);
        assertThat(event.get("source").asText()).isEqualTo("mmda.receipt");
        assertThat(event.get("detail-type").asText()).isEqualTo("ReceiptIssued");
        assertThat(event.get("detail").get("signature").asText()).isEqualTo(receipt.get("signature").s());
        assertThat(event.get("detail").get("issuedAt").asText()).isEqualTo(receipt.get("issuedAt").s());

        JsonNode fromApi = findReceipt(requestId).orElseThrow();
        assertThat(fromApi.get("signature").asText()).isEqualTo(receipt.get("signature").s());
        assertThat(fromApi.get("issuedAt").asText()).isEqualTo(receipt.get("issuedAt").s());
    }

    @Test
    void manualIssueCreatesAReceiptReadableThroughTheApi() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String body = JSON.writeValueAsString(Map.of("requestId", requestId, "signature", "manual-sig", "payloadBytes", 7));

        HttpResponse<String> accepted = HTTP.send(HttpRequest.newBuilder(receiptsUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofMinutes(2))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(JSON.readTree(accepted.body()).get("executionArn").asText()).contains("receipt-flow");

        JsonNode receipt = await().atMost(FLOW_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> findReceipt(requestId), Optional::isPresent)
                .orElseThrow();
        assertThat(receipt.get("signature").asText()).isEqualTo("manual-sig");
        assertThat(receipt.get("payloadBytes").asLong()).isEqualTo(7L);
        assertThat(awaitReceiptIssued(requestId).get("detail").get("signature").asText()).isEqualTo("manual-sig");
    }

    @Test
    void unknownReceiptAnswersNotFound() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(receiptsUri + "/" + UUID.randomUUID())).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private static String baseUrlOf(ApiGatewayClient apiGateway, String apiName) {
        String endpoint = System.getProperty("aws.endpointUrl", "http://localhost:4566");
        String apiId = apiGateway.getRestApis(builder -> builder.limit(500)).items().stream()
                .filter(api -> api.name().equals(apiName))
                .map(RestApi::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "API " + apiName + " not found; deploy both stacks first"));
        return endpoint + "/restapis/" + apiId + "/" + STAGE + "/_user_request_";
    }

    private static Optional<JsonNode> findReceipt(String requestId) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(receiptsUri + "/" + requestId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? Optional.of(JSON.readTree(response.body())) : Optional.empty();
    }

    private static String submit(String payload) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(requestsUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .timeout(Duration.ofMinutes(2))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        return JSON.readTree(response.body()).get("requestId").asText();
    }

    private static JsonNode awaitReceiptIssued(String requestId) throws Exception {
        Message match = await().atMost(FLOW_TIMEOUT).pollInterval(Duration.ofSeconds(1))
                .until(() -> findMessageAbout(requestId), Optional::isPresent)
                .orElseThrow();
        SQS.deleteMessage(builder -> builder.queueUrl(captureQueueUrl).receiptHandle(match.receiptHandle()));
        return JSON.readTree(match.body());
    }

    private static Optional<Message> findMessageAbout(String requestId) {
        return SQS.receiveMessage(builder -> builder.queueUrl(captureQueueUrl).maxNumberOfMessages(10))
                .messages().stream()
                .filter(message -> message.body().contains(requestId))
                .findFirst();
    }
}

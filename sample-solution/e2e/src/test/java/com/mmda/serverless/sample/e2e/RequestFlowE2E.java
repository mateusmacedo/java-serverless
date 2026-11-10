package com.mmda.serverless.sample.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.RestApi;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Exercita a stack implantada no Floci pela API HTTP, do mesmo jeito que um cliente faria.
 * <p>
 * WHY: o Scheduler também roda o reprocess a cada 5 minutos, então os testes verificam o estado
 * final de cada fluxo em vez de contagens exatas de reinício.
 */
class RequestFlowE2E {

    private static final String STAGE = System.getProperty("e2e.stage", "local");
    private static final String SERVICE = "sample-solution";
    private static final String PREFIX = SERVICE + "-" + STAGE;
    private static final Duration EXECUTION_TIMEOUT = Duration.ofMinutes(3);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final SfnClient SFN = SfnClient.create();
    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.create();
    private static final SqsClient SQS = SqsClient.create();
    private static final LambdaClient LAMBDA = LambdaClient.create();

    private static URI requestsUri;
    private static String executionArnPrefix;

    @BeforeAll
    static void locateTheDeployedStack() {
        String endpoint = System.getProperty("aws.endpointUrl", "http://localhost:4566");
        try (ApiGatewayClient apiGateway = ApiGatewayClient.create()) {
            String apiId = apiGateway.getRestApis(builder -> builder.limit(500)).items().stream()
                    .filter(api -> api.name().equals(STAGE + "-" + SERVICE))
                    .map(RestApi::id)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "API " + STAGE + "-" + SERVICE + " not found; deploy the stack first"));
            requestsUri = URI.create(endpoint + "/restapis/" + apiId + "/" + STAGE + "/_user_request_/requests");
        }
        String stateMachineArn = SFN.listStateMachines().stateMachines().stream()
                .filter(machine -> machine.name().equals(PREFIX + "-request-flow"))
                .map(StateMachineListItem::stateMachineArn)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("State machine " + PREFIX + "-request-flow not found"));
        executionArnPrefix = stateMachineArn.replace(":stateMachine:", ":execution:") + ":";
    }

    @Test
    void validRequestIsPersistedAndAudited() throws Exception {
        String payload = "{\"customer\":\"e2e\",\"amount\":1}";

        String requestId = submit(payload);

        assertThat(awaitFinalStatus(requestId)).isEqualTo(ExecutionStatus.SUCCEEDED);
        Map<String, AttributeValue> item = DYNAMO_DB.getItem(builder -> builder
                .tableName(PREFIX + "-requests")
                .key(Map.of("requestId", AttributeValue.fromS(requestId)))).item();
        assertThat(item.get("signature").s()).hasSize(64);
        assertThat(item.get("payloadBytes").n()).isEqualTo(Integer.toString(payload.length()));
        JsonNode event = awaitAuditEvent(requestId);
        assertThat(event.get("source").asText()).isEqualTo("mmda.sample");
        assertThat(event.get("detail-type").asText()).isEqualTo("RequestCompleted");
    }

    @Test
    void invalidRequestIsRetriedUntilTheLimitAndThenDiscarded() throws Exception {
        String requestId = submit(JSON.writeValueAsString(Map.of("data", "x".repeat(5000))));

        assertThat(awaitFinalStatus(requestId)).isEqualTo(ExecutionStatus.FAILED);
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            invokeReprocess();
            assertThat(status(requestId + "-3")).contains(ExecutionStatus.FAILED);
        });
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            invokeReprocess();
            assertThat(deadLetterQueueHas(requestId)).isFalse();
        });

        assertThat(status(requestId + "-2")).contains(ExecutionStatus.FAILED);
        assertThat(status(requestId + "-4")).isEmpty();
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

    private static ExecutionStatus awaitFinalStatus(String executionName) {
        return await().atMost(EXECUTION_TIMEOUT).pollInterval(Duration.ofSeconds(2))
                .until(() -> status(executionName).orElse(ExecutionStatus.RUNNING),
                        current -> current != ExecutionStatus.RUNNING);
    }

    private static Optional<ExecutionStatus> status(String executionName) {
        try {
            return Optional.of(SFN.describeExecution(builder -> builder
                    .executionArn(executionArnPrefix + executionName)).status());
        } catch (ExecutionDoesNotExistException notStarted) {
            return Optional.empty();
        }
    }

    private static void invokeReprocess() {
        LAMBDA.invoke(builder -> builder
                .functionName(PREFIX + "-reprocess")
                .payload(SdkBytes.fromUtf8String("{\"maxMessages\":10}")));
    }

    private static boolean deadLetterQueueHas(String requestId) {
        String queueUrl = SQS.getQueueUrl(builder -> builder.queueName(PREFIX + "-dlq")).queueUrl();
        return SQS.receiveMessage(builder -> builder.queueUrl(queueUrl).maxNumberOfMessages(10).visibilityTimeout(0))
                .messages().stream()
                .anyMatch(message -> message.body().contains(requestId));
    }

    private static JsonNode awaitAuditEvent(String requestId) {
        String queueUrl = SQS.getQueueUrl(builder -> builder.queueName(PREFIX + "-audit")).queueUrl();
        Message match = await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .until(() -> SQS.receiveMessage(builder -> builder.queueUrl(queueUrl).maxNumberOfMessages(10))
                                .messages().stream()
                                .filter(message -> message.body().contains(requestId))
                                .findFirst().orElse(null),
                        Objects::nonNull);
        SQS.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(match.receiptHandle()));
        try {
            return JSON.readTree(match.body());
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid audit event: " + match.body(), exception);
        }
    }
}

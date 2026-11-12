package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.provider.domain.EventIds;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.function.adapter.aws.FunctionInvoker;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * O handler da Lambda ({@code FunctionInvoker}) com eventos no formato do serviço: confere a desserialização
 * de {@code SQSEvent} e {@code DynamodbEvent} e a resposta de falha parcial (§7 e §14).
 */
class FunctionInvokerIT extends FlociIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WORKER_TIMEOUT = Duration.ofSeconds(25);
    private static final Map<String, String> ENVIRONMENT = new LinkedHashMap<>();

    @BeforeAll
    static void lambdaEnvironment() {
        ENVIRONMENT.put("MAIN_CLASS", FunctionsApplication.class.getName());
        ENVIRONMENT.put("TABLE_NAME", TABLE);
        ENVIRONMENT.put("PAYLOADS_BUCKET", BUCKET);
        ENVIRONMENT.put("EVENT_BUS_NAME", EVENT_BUS);
        ENVIRONMENT.put("QUERY_KEY_SECRET_ID", QUERY_KEY_SECRET);
        ENVIRONMENT.put("PROVIDERS_BASE_URL", PROVIDERS.baseUrl());
        ENVIRONMENT.put("S3_PATH_STYLE_ACCESS", "true");
        ENVIRONMENT.put("ALPHA_QUOTA_PER_MINUTE", "1000");
        ENVIRONMENT.put("INSURER_REQUESTS_QUEUE_URL", INSURER_QUEUE_URL);
        ENVIRONMENT.put("WORKER_TIMEOUT_SECONDS", Long.toString(WORKER_TIMEOUT.toSeconds()));
        ENVIRONMENT.forEach(System::setProperty);
    }

    @AfterAll
    static void clearEnvironment() {
        ENVIRONMENT.keySet().forEach(System::clearProperty);
    }

    @Test
    void sqsEventReachesTheInsurerWorkerThroughTheLambdaAdapter() throws IOException {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plusSeconds(240));

        String output = invoke("insurerWorker", sqsEvent(request.body(), Map.of()));

        assertThat(JSON.readTree(output).path("batchItemFailures").isArray()).isTrue();
        assertThat(JSON.readTree(output).path("batchItemFailures").size()).isZero();
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META")).isNotEmpty();
    }

    @Test
    void dlqAttemptOfTheSqsEventReachesTheDelayedRedelivery() throws IOException {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plusSeconds(240));
        EnrichmentWorkerIT.givenInFlightQuery(request,
                Instant.now().minus(WORKER_TIMEOUT).minus(QueryRecord.CLOCK_SKEW).plusSeconds(1), false);
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));

        String output = invoke("insurerWorker", sqsEvent(request.body(), Map.of(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE,
                Map.of("stringValue", "2", "stringListValues", List.of(), "binaryListValues", List.of(),
                        "dataType", "Number"))));

        assertThat(JSON.readTree(output).path("batchItemFailures").size()).isZero();
        Message delayed = EnrichmentWorkerIT.receiveDelayed(INSURER_QUEUE_URL);
        assertThat(delayed.messageAttributes()).containsKey(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE);
        assertThat(delayed.messageAttributes().get(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE).stringValue())
                .isEqualTo("2");
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));
    }

    @Test
    void dynamodbStreamEventReachesTheRelayThroughTheLambdaAdapter() throws IOException {
        String eventId = UUID.randomUUID().toString();
        String event;
        try (InputStream fixture = getClass().getResourceAsStream("/dynamodb-stream-event.json")) {
            event = new String(fixture.readAllBytes(), StandardCharsets.UTF_8).replace("${eventId}", eventId);
        }
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        JSON.readTree(event).path("Records").get(0).path("dynamodb").path("NewImage").properties()
                .forEach(entry -> item.put(entry.getKey(), entry.getValue().has("S")
                        ? AttributeValue.fromS(entry.getValue().path("S").asString())
                        : AttributeValue.fromN(entry.getValue().path("N").asString())));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(item));

        String output = invoke("outboxRelay", event);

        assertThat(JSON.readTree(output).path("batchItemFailures").size()).isZero();
        assertThat(attribute(item("OUTBOX#" + eventId, "META"), "publishedAt")).isPresent();
    }

    private static String sqsEvent(String body, Map<String, Object> messageAttributes) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", UUID.randomUUID().toString());
        message.put("receiptHandle", "AQEBwJnKyrHigUMZj6rYigCgxlaS3SLy0a");
        message.put("body", body);
        message.put("attributes", Map.of("ApproximateReceiveCount", "1", "SentTimestamp", "1794430800000",
                "SenderId", "AIDAIENQZJOLO23YVJ4VO", "ApproximateFirstReceiveTimestamp", "1794430800001"));
        message.put("messageAttributes", messageAttributes);
        message.put("md5OfBody", "e4e68fb7bd0e697a0ae8f1bb342846b3");
        message.put("eventSource", "aws:sqs");
        message.put("eventSourceARN", "arn:aws:sqs:us-east-1:000000000000:it-insurer-requests");
        message.put("awsRegion", "us-east-1");
        return JSON.writeValueAsString(Map.of("Records", List.of(message)));
    }

    private static String invoke(String function, String event) throws IOException {
        FunctionInvoker invoker = new FunctionInvoker(function);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        invoker.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);
        return output.toString(StandardCharsets.UTF_8);
    }
}

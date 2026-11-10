package com.mmda.serverless.sample.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * As quatro funções com clients reais contra o Floci. Os recursos são criados antes do contexto
 * Spring, com os mesmos papéis que têm no serverless.yml.
 */
@SpringBootTest(classes = FunctionsApplication.class)
class RequestFlowFunctionsIT extends FlociIntegrationTest {

    private static final String BUCKET = "it-payloads";
    private static final String TABLE = "it-requests";
    private static final String EVENT_BUS = "it-events";
    private static final String DEAD_LETTER_QUEUE = "it-dlq";
    private static final String MAX_PAYLOAD_PARAMETER = "/it/max-payload-bytes";
    private static final String SIGNING_SECRET = "it/signing-key";
    private static final String SIGNING_KEY = "it-signing-key";

    private static final S3Client S3 = S3Client.builder().forcePathStyle(true).build();
    private static final SqsClient SQS = SqsClient.create();
    private static final SfnClient SFN = SfnClient.create();
    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.create();

    private static final String TOPIC_ARN;
    private static final String STATE_MACHINE_ARN;
    private static final String DEAD_LETTER_QUEUE_URL;
    private static final String AUDIT_QUEUE_URL;
    private static final String NOTIFICATION_QUEUE_URL;

    static {
        S3.createBucket(builder -> builder.bucket(BUCKET));
        DYNAMO_DB.createTable(builder -> builder
                .tableName(TABLE)
                .billingMode("PAY_PER_REQUEST")
                .attributeDefinitions(a -> a.attributeName("requestId").attributeType(ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("requestId").keyType(KeyType.HASH)));
        try (SsmClient ssm = SsmClient.create(); SecretsManagerClient secrets = SecretsManagerClient.create()) {
            ssm.putParameter(builder -> builder.name(MAX_PAYLOAD_PARAMETER).type(ParameterType.STRING).value("4096"));
            secrets.createSecret(builder -> builder.name(SIGNING_SECRET).secretString(SIGNING_KEY));
        }

        DEAD_LETTER_QUEUE_URL = SQS.createQueue(builder -> builder.queueName(DEAD_LETTER_QUEUE)).queueUrl();
        AUDIT_QUEUE_URL = SQS.createQueue(builder -> builder.queueName("it-audit")).queueUrl();
        NOTIFICATION_QUEUE_URL = SQS.createQueue(builder -> builder.queueName("it-notifications-capture")).queueUrl();

        try (SnsClient sns = SnsClient.create(); EventBridgeClient events = EventBridgeClient.create()) {
            TOPIC_ARN = sns.createTopic(builder -> builder.name("it-notifications")).topicArn();
            sns.subscribe(builder -> builder.topicArn(TOPIC_ARN).protocol("sqs").endpoint(queueArn(NOTIFICATION_QUEUE_URL)));

            events.createEventBus(builder -> builder.name(EVENT_BUS));
            events.putRule(builder -> builder
                    .name("it-request-completed")
                    .eventBusName(EVENT_BUS)
                    .eventPattern("{\"source\":[\"mmda.sample\"],\"detail-type\":[\"RequestCompleted\"]}"));
            events.putTargets(builder -> builder
                    .rule("it-request-completed")
                    .eventBusName(EVENT_BUS)
                    .targets(target -> target.id("audit").arn(queueArn(AUDIT_QUEUE_URL))));
        }

        STATE_MACHINE_ARN = SFN.createStateMachine(builder -> builder
                .name("it-request-flow")
                .roleArn("arn:aws:iam::000000000000:role/it-request-flow")
                .definition("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}"))
                .stateMachineArn();
    }

    @DynamicPropertySource
    static void sampleProperties(DynamicPropertyRegistry registry) {
        registry.add("BUCKET_NAME", () -> BUCKET);
        registry.add("TABLE_NAME", () -> TABLE);
        registry.add("TOPIC_ARN", () -> TOPIC_ARN);
        registry.add("EVENT_BUS_NAME", () -> EVENT_BUS);
        registry.add("DEAD_LETTER_QUEUE_NAME", () -> DEAD_LETTER_QUEUE);
        registry.add("STATE_MACHINE_ARN", () -> STATE_MACHINE_ARN);
        registry.add("MAX_PAYLOAD_PARAMETER", () -> MAX_PAYLOAD_PARAMETER);
        registry.add("SIGNING_SECRET_ID", () -> SIGNING_SECRET);
        registry.add("S3_PATH_STYLE_ACCESS", () -> "true");
    }

    @Autowired
    private ValidateFunction validate;

    @Autowired
    private PersistFunction persist;

    @Autowired
    private NotifyFunction notify;

    @Autowired
    private ReprocessFunction reprocess;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validateSignsThePayloadStoredInS3() throws Exception {
        String payload = "{\"customer\":\"acme\",\"amount\":42}";
        upload("requests/it-validate.json", payload);

        RequestContext result = validate.apply(new RequestContext("it-validate", "requests/it-validate.json", 1, null, null));

        assertThat(result.payloadBytes()).isEqualTo(payload.length());
        assertThat(result.signature()).isEqualTo(hmac(SIGNING_KEY, payload));
    }

    @Test
    void validateRejectsAPayloadAboveTheLimitStoredInSsm() {
        upload("requests/it-large.json", "x".repeat(5000));

        assertThatThrownBy(() -> validate.apply(new RequestContext("it-large", "requests/it-large.json", 1, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Payload of 5000 bytes exceeds the limit of 4096 bytes");
    }

    @Test
    void persistWritesTheItemToDynamoDb() {
        persist.apply(new RequestContext("it-persist", "requests/it-persist.json", 1, "sig", 7L));

        Map<String, AttributeValue> item = DYNAMO_DB
                .getItem(builder -> builder.tableName(TABLE).key(Map.of("requestId", AttributeValue.fromS("it-persist"))))
                .item();
        assertThat(item.get("signature").s()).isEqualTo("sig");
        assertThat(item.get("payloadBytes").n()).isEqualTo("7");
        assertThat(item.get("attempt").n()).isEqualTo("1");
    }

    @Test
    void notifyReachesTheTopicSubscriberAndTheAuditRule() {
        notify.apply(new RequestContext("it-notify", "requests/it-notify.json", 1, "sig", 7L));

        JsonNode event = awaitSingleMessage(AUDIT_QUEUE_URL);
        assertThat(event.get("source").asText()).isEqualTo("mmda.sample");
        assertThat(event.get("detail-type").asText()).isEqualTo("RequestCompleted");
        assertThat(event.get("detail").get("requestId").asText()).isEqualTo("it-notify");

        JsonNode notification = awaitSingleMessage(NOTIFICATION_QUEUE_URL);
        JsonNode message = readJson(notification.has("Message") ? notification.get("Message").asText() : notification.toString());
        assertThat(message.get("requestId").asText()).isEqualTo("it-notify");
    }

    @Test
    void reprocessRestartsBelowTheLimitAndDiscardsAtTheLimit() {
        sendToDeadLetterQueue("it-retry", 1);
        sendToDeadLetterQueue("it-exhausted", 3);

        int restarted = 0;
        int discarded = 0;
        for (int round = 0; round < 5 && restarted + discarded < 2; round++) {
            ReprocessResult result = reprocess.apply(new ReprocessCommand(10));
            restarted += result.restarted();
            discarded += result.discarded();
        }

        assertThat(restarted).isEqualTo(1);
        assertThat(discarded).isEqualTo(1);
        List<String> executions = SFN.listExecutions(builder -> builder.stateMachineArn(STATE_MACHINE_ARN))
                .executions().stream().map(ExecutionListItem::name).toList();
        assertThat(executions).contains("it-retry-2").doesNotContain("it-exhausted-4");
        assertThat(SQS.receiveMessage(builder -> builder.queueUrl(DEAD_LETTER_QUEUE_URL)).messages()).isEmpty();
    }

    private static void upload(String key, String content) {
        S3.putObject(builder -> builder.bucket(BUCKET).key(key), RequestBody.fromString(content));
    }

    private static void sendToDeadLetterQueue(String requestId, int attempt) {
        String body = """
                {"requestId":"%s","s3Key":"requests/%s.json","attempt":%d,
                 "error":{"Error":"com.mmda.serverless.sample.functions.InvalidRequestException","Cause":"{}"}}
                """.formatted(requestId, requestId, attempt);
        SQS.sendMessage(builder -> builder.queueUrl(DEAD_LETTER_QUEUE_URL).messageBody(body));
    }

    private JsonNode awaitSingleMessage(String queueUrl) {
        List<Message> messages = await().atMost(Duration.ofSeconds(20))
                .until(() -> SQS.receiveMessage(builder -> builder.queueUrl(queueUrl).waitTimeSeconds(1)).messages(),
                        received -> !received.isEmpty());
        return readJson(messages.getFirst().body());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JSON: " + json, exception);
        }
    }

    private static String queueArn(String queueUrl) {
        return SQS.getQueueAttributes(builder -> builder.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static String hmac(String key, String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}

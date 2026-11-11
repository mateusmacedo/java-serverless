package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Relay registro a registro e republicador, com o barramento e a tabela no Floci (desenho, §7 e §14). O
 * erro transitório, que o Floci não provoca, está no {@code OutboxRelayFunctionTest}.
 */
class OutboxRelayIT extends FlociIntegrationTest {

    private static final Map<String, JsonNode> CAPTURED = new ConcurrentHashMap<>();

    @Autowired
    private OutboxRelayFunction relay;

    @Autowired
    private ReconcilerFunction reconciler;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void relayPublishesEachOutboxRecordAndMarksItPublished() {
        String first = givenOutbox(Instant.now(), true);
        String second = givenOutbox(Instant.now(), true);

        StreamsEventResponse response = relay.apply(streamEvent(first, second));

        assertThat(response.getBatchItemFailures()).isEmpty();
        awaitPublished(first, second);
        for (String eventId : List.of(first, second)) {
            Map<String, AttributeValue> outbox = item("OUTBOX#" + eventId, "META");
            assertThat(attribute(outbox, "publishedAt")).isPresent();
            assertThat(outbox).doesNotContainKeys("gsi2pk", "gsi2sk");
        }
        assertThat(CAPTURED.get(first).path("source").asString()).isEqualTo("mmda.provider");
        assertThat(CAPTURED.get(first).path("detail-type").asString()).isEqualTo("InsurerResultRecorded");
    }

    @Test
    void permanentFailureIsRecordedOnTheItemAndDoesNotBlockTheBatch() {
        String broken = givenOutbox(Instant.now(), false);
        String healthy = givenOutbox(Instant.now(), true);

        StreamsEventResponse response = relay.apply(streamEvent(broken, healthy));

        assertThat(response.getBatchItemFailures()).isEmpty();
        awaitPublished(healthy);
        Map<String, AttributeValue> failed = item("OUTBOX#" + broken, "META");
        assertThat(attribute(failed, "attempts")).contains("1");
        assertThat(attribute(failed, "lastError")).isPresent();
        assertThat(attribute(failed, "gsi2pk")).contains("OUTBOX_PENDING");
    }

    @Test
    void itemThatFailsPermanentlyGoesToTheEndOfTheReconcilerQueue() {
        String broken = givenOutbox(Instant.now().minusSeconds(120), false);
        String failedAt = Timestamps.format(Instant.now());

        relay.apply(streamEvent(broken));

        assertThat(attribute(item("OUTBOX#" + broken, "META"), "gsi2sk")).hasValueSatisfying(
                gsi2sk -> assertThat(gsi2sk).isGreaterThanOrEqualTo(failedAt));
    }

    @Test
    void republishingAnEventAlreadyPublishedHasNoEffectOnTheItem() {
        String eventId = givenOutbox(Instant.now(), true);

        relay.apply(streamEvent(eventId));
        relay.apply(streamEvent(eventId));

        awaitPublished(eventId);
        assertThat(attribute(item("OUTBOX#" + eventId, "META"), "publishedAt")).isPresent();
    }

    @Test
    void reconcilerRepublishesOnlyStalePendingOutbox() {
        String stale = givenOutbox(Instant.now().minusSeconds(120), true);
        String recent = givenOutbox(Instant.now(), true);

        ReconcileResult result = reconciler.apply(new ReconcileCommand(500));

        assertThat(result.republished()).isPositive();
        awaitPublished(stale);
        assertThat(attribute(item("OUTBOX#" + stale, "META"), "publishedAt")).isPresent();
        assertThat(attribute(item("OUTBOX#" + recent, "META"), "gsi2pk")).contains("OUTBOX_PENDING");
    }

    /** Item como o {@code OutboxStore} grava; {@code complete = false} deixa o item sem {@code detail}. */
    private String givenOutbox(Instant createdAt, boolean complete) {
        String eventId = UUID.randomUUID().toString();
        String created = Timestamps.format(createdAt);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS("OUTBOX#" + eventId));
        item.put("sk", AttributeValue.fromS("META"));
        item.put("type", AttributeValue.fromS("OUTBOX"));
        item.put("eventId", AttributeValue.fromS(eventId));
        item.put("detailType", AttributeValue.fromS("InsurerResultRecorded"));
        item.put("createdAt", AttributeValue.fromS(created));
        item.put("attempts", AttributeValue.fromN("0"));
        item.put("gsi2pk", AttributeValue.fromS("OUTBOX_PENDING"));
        item.put("gsi2sk", AttributeValue.fromS(created));
        if (complete) {
            item.put("detail", AttributeValue.fromS("{\"eventId\":\"" + eventId + "\",\"schemaVersion\":\"1\"}"));
        }
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(item));
        return eventId;
    }

    /** Registros de {@code INSERT} com o {@code NewImage} lido da tabela, como o stream entrega. */
    private static DynamodbEvent streamEvent(String... eventIds) {
        List<DynamodbEvent.DynamodbStreamRecord> records = new ArrayList<>();
        long sequence = System.nanoTime();
        for (String eventId : eventIds) {
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> image =
                    new HashMap<>();
            item("OUTBOX#" + eventId, "META").forEach((name, value) -> image.put(name,
                    value.s() != null
                            ? new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue()
                                    .withS(value.s())
                            : new com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue()
                                    .withN(value.n())));
            StreamRecord stream = new StreamRecord().withNewImage(image).withSequenceNumber(Long.toString(sequence++));
            DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
            record.setEventName("INSERT");
            record.setDynamodb(stream);
            records.add(record);
        }
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(records);
        return event;
    }

    private void awaitPublished(String... eventIds) {
        Set<String> expected = Set.of(eventIds);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (Message message : SQS.receiveMessage(builder -> builder
                    .queueUrl(CAPTURE_QUEUE_URL)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)).messages()) {
                JsonNode event = objectMapper.readTree(message.body());
                CAPTURED.put(event.path("detail").path("eventId").asString(), event);
                SQS.deleteMessage(builder -> builder.queueUrl(CAPTURE_QUEUE_URL).receiptHandle(message.receiptHandle()));
            }
            assertThat(CAPTURED.keySet()).containsAll(expected);
        });
    }
}

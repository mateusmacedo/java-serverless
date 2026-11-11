package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QuotaWindow;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code enrichmentWorker} contra o Floci e o provedor simulado em processo (desenho, §8 e §9). */
class EnrichmentWorkerIT extends FlociIntegrationTest {

    private static final Duration DEADLINE = Duration.ofMinutes(4);

    @Autowired
    private EnrichmentWorkerFunction worker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private S3Client s3;

    @Test
    void t01_enrichmentIsResolvedAsMissWithEvidence() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", Instant.now().plus(DEADLINE));

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("COMPLETED");
        assertThat(attribute(query, "operation")).contains("ENRICH");
        assertThat(attribute(query, "clientReference")).contains(request.queryId());
        assertThat(attribute(query, "queryKey")).hasValueSatisfying(key -> assertThat(key).hasSize(64));
        assertThat(query).doesNotContainKeys("gsi2pk", "fencingVersion");
        List<Map<String, AttributeValue>> attempts = queryItems(request.queryId(), "ATTEMPT#");
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attribute(attempt, "startedAt")).isPresent();
            assertThat(attribute(attempt, "finishedAt")).isPresent();
            assertThat(attribute(attempt, "outcome")).contains("FOUND");
            assertThat(attribute(attempt, "httpStatus")).contains("200");
        });

        JsonNode detail = outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()));
        assertThat(detail.path("workRef").asString()).isEqualTo(request.workRef());
        assertThat(detail.path("quoteId").asString()).isEqualTo(request.quoteId());
        assertThat(detail.path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("queryId").asString()).isEqualTo(request.queryId());
        assertThat(detail.path("reuseDecision").asString()).isEqualTo("MISS");
        assertThat(detail.path("resultType").asString()).isEqualTo("FOUND");
        assertThat(detail.path("result").path("vehicle").path("year").asInt()).isEqualTo(2019);
        assertThat(detail.path("result").path("principalDriver").path("ref").asString()).isEqualTo("driver-ana");
        assertThat(detail.has("errorClass")).isFalse();
        assertThat(detail.has("notFoundRefs")).isFalse();
        Instant observedAt = Timestamps.parse(detail.path("occurredAt").asString());
        assertThat(Timestamps.parse(detail.path("reusableUntil").asString())).isAfter(observedAt.plusSeconds(890));

        Map<String, AttributeValue> evidence = item("QUERY#" + request.queryId(),
                "EVIDENCE#" + detail.path("evidenceId").asString());
        assertThat(attribute(evidence, "resultType")).contains("FOUND");
        assertThat(attribute(evidence, "checksum")).isPresent();
        String rawResponseRef = attribute(evidence, "rawResponseRef").orElseThrow();
        assertThat(s3.getObjectAsBytes(builder -> builder.bucket(BUCKET).key(rawResponseRef)).asUtf8String())
                .contains("\"modelYear\":2019");
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
    }

    @Test
    void redeliveryOfAResolvedRequestNeverCallsAgain() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2020", Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));
        SQSBatchResponse redelivery = worker.apply(sqsEvent(request.body()));

        assertThat(redelivery.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
    }

    @Test
    void flakyProviderIsRetriedByTheWorkerUntilFound() {
        TestRequests.Request request = TestRequests.enrichment(ProviderStub.VEHICLE_FLAKY, Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(3);
        assertThat(queryItems(request.queryId(), "ATTEMPT#"))
                .extracting(attempt -> attribute(attempt, "outcome").orElseThrow())
                .containsExactly("TRANSIENT", "TRANSIENT", "FOUND");
        assertThat(outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()))
                .path("resultType").asString()).isEqualTo("FOUND");
    }

    @Test
    void providerDownFailsAfterTheWorkerRetriesWithoutInventingData() {
        TestRequests.Request request = TestRequests.enrichment(ProviderStub.VEHICLE_ENRICHMENT_DOWN,
                Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(3);
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("FAILED");
        JsonNode detail = outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()));
        assertThat(detail.path("resultType").asString()).isEqualTo("FAILED");
        assertThat(detail.path("errorClass").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(detail.has("result")).isFalse();
        assertThat(detail.has("reusableUntil")).isFalse();
    }

    @Test
    void declaredNotFoundIsNotFoundWithTheReferences() {
        TestRequests.Request request = TestRequests.enrichment(ProviderStub.VEHICLE_UNKNOWN, Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        JsonNode detail = outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()));
        assertThat(detail.path("resultType").asString()).isEqualTo("NOT_FOUND");
        assertThat(detail.path("notFoundRefs").get(0).asString()).isEqualTo("vehicleRef");
        assertThat(detail.has("reusableUntil")).isTrue();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
    }

    @Test
    void expiredDeadlineFailsWithoutCallingTheProvider() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", Instant.now().minusSeconds(1));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).isEmpty();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("FAILED");
        assertThat(outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()))
                .path("errorClass").asString()).isEqualTo("DEADLINE_EXCEEDED_BEFORE_CALL");
    }

    @Test
    void lostResponseLeavesTheQueryUnknownAndFailsTheQuote() {
        TestRequests.Request request = TestRequests.enrichment(ProviderStub.VEHICLE_SLOW, Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("UNKNOWN");
        assertThat(attribute(query, "gsi2pk")).contains("QUERY_UNKNOWN");
        assertThat(attribute(queryItems(request.queryId(), "ATTEMPT#").getFirst(), "outcome")).contains("NO_RESPONSE");
        assertThat(outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()))
                .path("errorClass").asString()).isEqualTo("PROVIDER_NO_RESPONSE");
    }

    @Test
    void redeliveryWithAnOpenAttemptNeverRepeatsTheCall() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        givenInFlightQueryWithOpenAttempt(request);

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("UNKNOWN");
        assertThat(outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()))
                .path("errorClass").asString()).isEqualTo("INTERRUPTED_ATTEMPT");
    }

    @Test
    void duplicateDeliveryWhileAnotherIsCallingIsDelayedWithoutTouchingTheQuery() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        givenInFlightQuery(request, Instant.now().minusSeconds(2), false);
        SQS.purgeQueue(builder -> builder.queueUrl(ENRICHMENT_QUEUE_URL));

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(request.queryId(), request.workRef()), "META"))
                .isEmpty();
        assertThat(queuedMessages(ENRICHMENT_QUEUE_URL)).isEqualTo(1);
        SQS.purgeQueue(builder -> builder.queueUrl(ENRICHMENT_QUEUE_URL));
    }

    @Test
    void duplicateDeliveryDuringTheBackoffOfAnotherIsDelayedWithoutFailingTheQuote() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        givenInFlightQuery(request, Instant.now().minusSeconds(1), true);
        SQS.purgeQueue(builder -> builder.queueUrl(ENRICHMENT_QUEUE_URL));

        worker.apply(sqsEvent(request.body()));

        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(request.queryId(), request.workRef()), "META"))
                .isEmpty();
        assertThat(queuedMessages(ENRICHMENT_QUEUE_URL)).isEqualTo(1);
        SQS.purgeQueue(builder -> builder.queueUrl(ENRICHMENT_QUEUE_URL));
    }

    @Test
    void exhaustedQuotaDelaysTheRequestUntilTheWindowTurns() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", Instant.now().plus(DEADLINE),
                CAPPED_PROVIDER);
        givenExhaustedQuota(CAPPED_PROVIDER);
        SQS.purgeQueue(builder -> builder.queueUrl(ENRICHMENT_QUEUE_URL));

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(item("QUERY#" + request.queryId(), "META")).isEmpty();
        Map<QueueAttributeName, String> queue = SQS.getQueueAttributes(builder -> builder
                .queueUrl(ENRICHMENT_QUEUE_URL)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes();
        assertThat(Integer.parseInt(queue.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED))
                + Integer.parseInt(queue.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))).isEqualTo(1);
        SQS.purgeQueue(builder -> builder.queueUrl(ENRICHMENT_QUEUE_URL));
    }

    @Test
    void exhaustedQuotaThatDoesNotFitTheDeadlineFailsWithoutCalling() {
        TestRequests.Request request = TestRequests.enrichment("vehicle-hatch-2019", deadlineAtTheWindowTurn(),
                CAPPED_PROVIDER);
        givenExhaustedQuota(CAPPED_PROVIDER);

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef()))
                .path("errorClass").asString()).isEqualTo("QUOTA_EXHAUSTED");
    }

    @Test
    void messageThatIsNotAnEventReturnsToTheQueue() {
        SQSBatchResponse response = worker.apply(sqsEvent("{\"not\":\"an event\"}"));

        assertThat(response.getBatchItemFailures())
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("message-1");
    }

    private JsonNode outboxDetail(String eventId) {
        Map<String, AttributeValue> outbox = item("OUTBOX#" + eventId, "META");
        assertThat(attribute(outbox, "type")).contains("OUTBOX");
        assertThat(attribute(outbox, "detailType")).contains("EnrichmentResolved");
        assertThat(attribute(outbox, "gsi2pk")).contains("OUTBOX_PENDING");
        JsonNode detail = objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
        assertThat(detail.path("eventId").asString()).isEqualTo(eventId);
        assertThat(detail.path("schemaVersion").asString()).isEqualTo("1");
        return detail;
    }

    /** Consulta em voo cuja entrega já morreu: a tentativa aberta é mais antiga que o timeout do worker. */
    static void givenInFlightQueryWithOpenAttempt(TestRequests.Request request) {
        givenInFlightQuery(request, Instant.now().minusSeconds(200), false);
    }

    /**
     * Consulta em voo com uma tentativa iniciada em {@code startedAt}; {@code finished} a encerra como
     * transitória, como no backoff entre tentativas.
     */
    static void givenInFlightQuery(TestRequests.Request request, Instant startedAt, boolean finished) {
        String started = Timestamps.format(startedAt);
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "gsi2pk", AttributeValue.fromS("QUERY_IN_FLIGHT"),
                "gsi2sk", AttributeValue.fromS(started))));
        Map<String, AttributeValue> attempt = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(started)));
        if (finished) {
            attempt.put("finishedAt", AttributeValue.fromS(started));
            attempt.put("outcome", AttributeValue.fromS("TRANSIENT"));
        }
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(attempt));
    }

    /** Mensagens visíveis e adiadas na fila. */
    static int queuedMessages(String queueUrl) {
        Map<QueueAttributeName, String> queue = SQS.getQueueAttributes(builder -> builder
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED,
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes();
        return Integer.parseInt(queue.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED))
                + Integer.parseInt(queue.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
    }

    /**
     * Prazo na virada da janela da quota: o reenvio adiado nunca cabe nele. Com menos de 3 s até a virada,
     * espera a próxima janela, para o prazo não vencer antes da conferência.
     */
    static Instant deadlineAtTheWindowTurn() {
        QuotaWindow window = QuotaWindow.of(Instant.now(), Duration.ofMinutes(1));
        if (Duration.between(Instant.now(), window.end()).toSeconds() < 3) {
            try {
                Thread.sleep(Duration.between(Instant.now(), window.end()).plusMillis(100));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            window = QuotaWindow.of(Instant.now(), Duration.ofMinutes(1));
        }
        return window.end();
    }

    /** Esgota a janela corrente e a seguinte, para a virada do minuto não mudar o resultado. */
    static void givenExhaustedQuota(String provider) {
        Instant now = Instant.now();
        for (Instant instant : List.of(now, now.plusSeconds(60))) {
            String key = QuotaWindow.of(instant, Duration.ofMinutes(1)).key(provider, "default");
            DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                    "pk", AttributeValue.fromS(key),
                    "sk", AttributeValue.fromS("META"),
                    "type", AttributeValue.fromS("QUOTA"),
                    "used", AttributeValue.fromN("1"))));
        }
    }

    static SQSEvent sqsEvent(String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId("message-1");
        message.setBody(body);
        message.setReceiptHandle(UUID.randomUUID().toString());
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));
        return event;
    }
}

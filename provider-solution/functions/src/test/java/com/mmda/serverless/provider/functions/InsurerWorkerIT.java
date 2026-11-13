package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.receiveDelayed;
import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.sqsEvent;
import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.startedSoThatTheDeliveryEndsInASecond;
import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code insurerWorker} com a seguradora síncrona alpha (desenho, §8,
 * "Seguradora").
 */
class InsurerWorkerIT extends FlociIntegrationTest {

    private static final Duration DEADLINE = Duration.ofMinutes(4);

    @Autowired
    private InsurerWorkerFunction worker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private S3Client s3;

    @Autowired
    private ProviderProperties properties;

    @Test
    void t01_singleInsurerOfferIsRecordedWithEvidence() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE));

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("COMPLETED");
        assertThat(attribute(query, "operation")).contains("QUOTE");
        assertThat(attribute(query, "insurerId")).contains("alpha");
        assertThat(attribute(query, "clientReference")).contains(request.queryId());
        assertThat(query).doesNotContainKeys("queryKey", "gsi2pk");

        JsonNode detail = outboxDetail(EventIds.insurerResultRecorded(request.queryId()));
        assertThat(detail.path("workRef").asString()).isEqualTo(request.workRef());
        assertThat(detail.path("quoteId").asString()).isEqualTo(request.quoteId());
        assertThat(detail.path("insurerId").asString()).isEqualTo("alpha");
        assertThat(detail.path("queryId").asString()).isEqualTo(request.queryId());
        assertThat(detail.path("attemptRef").asString()).isEqualTo(Identities.attemptRef(request.queryId(), 1));
        assertThat(detail.path("status").asString()).isEqualTo("QUOTED");
        assertThat(detail.has("reasonCode")).isFalse();
        JsonNode offer = detail.path("offer");
        assertThat(offer.path("externalOfferId").asString()).startsWith("ALPHA-");
        assertThat(offer.path("insurerName").asString()).isEqualTo("Alpha Seguros");
        assertThat(offer.path("premiumTotal").isString()).isTrue();
        assertThat(offer.path("premiumTotal").asString()).isEqualTo("2450.00");
        assertThat(offer.path("currency").asString()).isEqualTo("BRL");
        assertThat(offer.path("installments").get(1).path("count").asInt()).isEqualTo(10);
        assertThat(offer.path("installments").get(1).path("amount").asString()).isEqualTo("245.00");
        assertThat(offer.path("coverages").get(0).path("code").asString()).isEqualTo("COLLISION");
        assertThat(offer.path("deductibles").get(0).path("coverageCode").asString()).isEqualTo("COLLISION");
        assertThat(offer.path("validUntil").asString()).isEqualTo("2026-12-01T00:00:00.000Z");

        String rawResponseRef = offer.path("rawResponseRef").asString();
        assertThat(s3.getObjectAsBytes(builder -> builder.bucket(BUCKET).key(rawResponseRef)).asUtf8String())
                .contains("\"offerNumber\"");
        Map<String, AttributeValue> evidence = queryItems(request.queryId(), "EVIDENCE#").getFirst();
        assertThat(attribute(evidence, "resultType")).contains("QUOTED");
        assertThat(attribute(evidence, "rawResponseRef")).contains(rawResponseRef);
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
    }

    @Test
    void redeliveryNeverRepeatsACallAlreadySent() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));
        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).hasSize(1);
    }

    @Test
    void declineIsACommercialResultWithoutRetry() {
        TestRequests.Request request = TestRequests.insurer(ProviderStub.VEHICLE_ALPHA_DECLINES,
                Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        JsonNode detail = outboxDetail(EventIds.insurerResultRecorded(request.queryId()));
        assertThat(detail.path("status").asString()).isEqualTo("DECLINED");
        assertThat(detail.path("reasonCode").asString()).isEqualTo("RISK_NOT_ACCEPTED");
        assertThat(detail.has("offer")).isFalse();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
    }

    @Test
    void unavailableInsurerIsRetriedThenRecordedAsUnavailable() {
        TestRequests.Request request = TestRequests.insurer(ProviderStub.VEHICLE_ALPHA_DOWN,
                Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(3);
        JsonNode detail = outboxDetail(EventIds.insurerResultRecorded(request.queryId()));
        assertThat(detail.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(detail.path("reasonCode").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(detail.path("attemptRef").asString()).isEqualTo(Identities.attemptRef(request.queryId(), 3));
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("FAILED");
    }

    @Test
    void expiredDeadlineIsUnavailableWithoutCalling() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().minusSeconds(1));

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        JsonNode detail = outboxDetail(EventIds.insurerResultRecorded(request.queryId()));
        assertThat(detail.path("status").asString()).isEqualTo("UNAVAILABLE");
        assertThat(detail.path("reasonCode").asString()).isEqualTo("DEADLINE_EXCEEDED_BEFORE_CALL");
        assertThat(detail.has("attemptRef")).isFalse();
    }

    @Test
    void lostResponseLeavesTheQueryUnknownWithoutResult() {
        TestRequests.Request request = TestRequests.insurer(ProviderStub.VEHICLE_SLOW, Instant.now().plus(DEADLINE));

        worker.apply(sqsEvent(request.body()));
        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("UNKNOWN");
        assertThat(attribute(query, "gsi2pk")).contains("QUERY_UNKNOWN");
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META")).isEmpty();
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isEmpty();
    }

    @Test
    void redeliveryWithAnOpenAttemptLeavesTheQueryUnknown() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        EnrichmentWorkerIT.givenInFlightQueryWithOpenAttempt(request);

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("UNKNOWN");
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META")).isEmpty();
    }

    @Test
    void duplicateDeliveryWhileAnotherIsCallingIsDelayedWithoutTouchingTheQuery() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        EnrichmentWorkerIT.givenInFlightQuery(request, Instant.now().minusSeconds(2), false);
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).hasSize(1);
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META")).isEmpty();
        assertThat(EnrichmentWorkerIT.queuedMessages(INSURER_QUEUE_URL)).isEqualTo(1);
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));
    }

    @Test
    void delayedRedeliveryKeepsTheDeadLetterAttemptOfTheMessage() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        EnrichmentWorkerIT.givenInFlightQuery(request, startedSoThatTheDeliveryEndsInASecond(properties), false);
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));

        assertThat(worker.apply(sqsEvent(request.body(), "2")).getBatchItemFailures()).isEmpty();

        Message delayed = receiveDelayed(INSURER_QUEUE_URL);
        assertThat(delayed.body()).isEqualTo(request.body());
        assertThat(delayed.messageAttributes()).as("the count of the dead-letter attempts does not restart")
                .containsKey(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE);
        assertThat(delayed.messageAttributes().get(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE).stringValue())
                .isEqualTo("2");
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));
    }

    @Test
    void redeliveryAfterFailuresBeforeSendingContinuesWithANewAttempt() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE));
        givenInFlightQueryWithAFinishedTransientAttempt(request);

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        assertThat(queryItems(request.queryId(), "ATTEMPT#"))
                .extracting(attempt -> attribute(attempt, "outcome").orElseThrow())
                .containsExactly("TRANSIENT", "QUOTED");
        assertThat(outboxDetail(EventIds.insurerResultRecorded(request.queryId())).path("attemptRef").asString())
                .isEqualTo(Identities.attemptRef(request.queryId(), 2));
    }

    @Test
    void unsupportedInsurerIsUnavailableWithoutCalling() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE),
                "gamma");

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(outboxDetail(EventIds.insurerResultRecorded(request.queryId())).path("reasonCode").asString())
                .isEqualTo("UNSUPPORTED_INSURER");
    }

    @Test
    void exhaustedQuotaThatDoesNotFitTheDeadlineIsUnavailableWithoutCalling() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019",
                EnrichmentWorkerIT.deadlineAtTheWindowTurn(), CAPPED_PROVIDER);
        EnrichmentWorkerIT.givenExhaustedQuota(CAPPED_PROVIDER);

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(outboxDetail(EventIds.insurerResultRecorded(request.queryId())).path("reasonCode").asString())
                .isEqualTo("QUOTA_EXHAUSTED");
    }

    private JsonNode outboxDetail(String eventId) {
        Map<String, AttributeValue> outbox = item("OUTBOX#" + eventId, "META");
        assertThat(attribute(outbox, "detailType")).contains("InsurerResultRecorded");
        JsonNode detail = objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
        assertThat(detail.path("eventId").asString()).isEqualTo(eventId);
        return detail;
    }

    private static void givenInFlightQueryWithAFinishedTransientAttempt(TestRequests.Request request) {
        String startedAt = Timestamps.format(Instant.now().minusSeconds(200));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"))));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(startedAt),
                "finishedAt", AttributeValue.fromS(startedAt),
                "outcome", AttributeValue.fromS("TRANSIENT"))));
    }
}

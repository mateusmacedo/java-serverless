package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.sqsEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.mmda.serverless.provider.api.CallbackApi;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code insurerWorker} com a seguradora assíncrona beta (desenho, §8,
 * "Seguradora" e "Callbacks"): o caminho
 * do {@code 202}, a metade do worker no {@code CORR#} e as pendências que a API
 * de callbacks guardou antes dela.
 * Os callbacks passam pelo código do {@code provider-api}
 * ({@link CallbackApi}), então as duas metades do encontro
 * e as duas conclusões, a da API e a do worker, são as reais.
 */
class InsurerCallbackIT extends FlociIntegrationTest {

    private static final Duration DEADLINE = Duration.ofMinutes(4);
    private static final String BETA = "beta";
    private static final String UNREADABLE_OFFER = "{\"outcome\":\"OFFER\",\"insurerName\":\"Beta Seguros\"}";
    /**
     * Atributos sem identidade nem horário, comparados pelo valor; os demais, só
     * pelo tipo.
     */
    private static final Set<String> COMPARED_BY_VALUE = Set.of("type", "state", "operation", "provider", "insurerId",
            "organizationId", "version", "sequence", "outcome", "errorClass", "httpStatus", "resultType",
            "normalizedResult", "processingStatus", "detailType", "attempts", "gsi2pk");

    @Autowired
    private InsurerWorkerFunction worker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private S3Client s3;

    private CallbackApi api;

    @BeforeEach
    void callbackApi() {
        api = new CallbackApi(DYNAMO_DB, s3, objectMapper, TABLE, BUCKET);
    }

    @Test
    void acceptedRequestKeepsTheQueryInFlightUntilTheCallback() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("IN_FLIGHT");
        assertThat(attribute(query, "gsi2pk")).contains("QUERY_IN_FLIGHT");
        assertThat(attribute(query, "providerRequestId")).contains(providerRequestId);
        assertThat(attribute(query, "requestEventId")).contains(request.eventId());
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
            assertThat(attribute(attempt, "providerRequestId")).contains(providerRequestId);
            assertThat(attribute(attempt, "acceptedAt")).isPresent();
            assertThat(attribute(attempt, "httpStatus")).contains("202");
            assertThat(attempt).doesNotContainKeys("finishedAt", "outcome");
        });
        Map<String, AttributeValue> correlation = item("CORR#beta#" + providerRequestId, "META");
        assertThat(attribute(correlation, "queryId")).contains(request.queryId());
        assertThat(attribute(correlation, "type")).contains("CORRELATION");
        assertThat(correlation).doesNotContainKey("pendingEvents");
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META")).isEmpty();
        assertThat(item("CONSUMED#" + request.eventId(), "META"))
                .as("the request is consumed with the result of the callback, never before it").isEmpty();
    }

    @Test
    void t12_callbackHeldBeforeThe202IsSettledByTheWorkerHalf() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        String externalEventId = givenCallbackWaitingForTheWorker(providerRequestId, offerResult());

        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("COMPLETED");
        assertThat(query).doesNotContainKey("gsi2pk");
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
            assertThat(attribute(attempt, "finishedAt")).isPresent();
            assertThat(attribute(attempt, "outcome")).contains("QUOTED");
            assertThat(attribute(attempt, "providerRequestId")).contains(providerRequestId);
        });
        JsonNode detail = outboxDetail(request.queryId());
        assertThat(detail.path("status").asString()).isEqualTo("QUOTED");
        assertThat(detail.path("insurerId").asString()).isEqualTo(BETA);
        assertThat(detail.path("workRef").asString()).isEqualTo(request.workRef());
        assertThat(detail.path("attemptRef").asString()).isEqualTo(Identities.attemptRef(request.queryId(), 1));
        assertThat(detail.path("offer").path("insurerName").asString()).isEqualTo("Beta Seguros");
        String rawResponseRef = detail.path("offer").path("rawResponseRef").asString();
        assertThat(rawResponseRef).isEqualTo("raw/" + request.queryId() + "/callback-" + externalEventId + ".json");
        assertThat(s3.getObjectAsBytes(builder -> builder.bucket(BUCKET).key(rawResponseRef)).asUtf8String())
                .contains(externalEventId);
        assertThat(queryItems(request.queryId(), "EVIDENCE#")).singleElement()
                .satisfies(evidence -> assertThat(attribute(evidence, "resultType")).contains("QUOTED"));
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
        assertInboxProcessed(externalEventId, request.queryId());
    }

    @Test
    void t12_callbackAfterTheWorkerHalfIsSettledByTheApi() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        worker.apply(sqsEvent(request.body()));
        String externalEventId = "bev_" + UUID.randomUUID();

        assertThat(api.receive(BETA, callback(externalEventId, providerRequestId, null, declinedResult())))
                .isEqualTo("PROCESSED");

        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        JsonNode detail = outboxDetail(request.queryId());
        assertThat(detail.path("status").asString()).isEqualTo("DECLINED");
        assertThat(detail.path("attemptRef").asString()).isEqualTo(Identities.attemptRef(request.queryId(), 1));
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
        assertInboxProcessed(externalEventId, request.queryId());
    }

    /**
     * O callback com {@code clientReference} chega durante a chamada e conclui a
     * consulta antes de o {@code 202}
     * ser gravado: o {@code 202} não muda a consulta concluída, e a metade do
     * worker no {@code CORR#} não acha
     * pendência nem grava outro resultado.
     */
    @Test
    void callbackWithTheClientReferenceDuringTheCallSettlesTheQueryBeforeThe202IsRecorded() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        String externalEventId = "bev_" + UUID.randomUUID();
        AtomicReference<String> processing = new AtomicReference<>();
        PROVIDERS.beforeAnswering(request.queryId(), () -> processing.set(api.receive(BETA,
                callback(externalEventId, providerRequestId, request.queryId(), offerResult()))));

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(processing.get()).isEqualTo("PROCESSED");
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
            assertThat(attribute(attempt, "finishedAt")).isPresent();
            assertThat(attribute(attempt, "outcome")).contains("QUOTED");
            assertThat(attribute(attempt, "providerRequestId")).contains(providerRequestId);
        });
        assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
        assertThat(outboxDetail(request.queryId()).path("status").asString()).isEqualTo("QUOTED");
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
        Map<String, AttributeValue> correlation = item("CORR#beta#" + providerRequestId, "META");
        assertThat(attribute(correlation, "queryId")).contains(request.queryId());
        assertThat(correlation).doesNotContainKey("pendingEvents");
        assertInboxProcessed(externalEventId, request.queryId());
    }

    /**
     * A conclusão do callback existe nos dois módulos (a API conclui quando é a
     * segunda metade; o worker, quando
     * ele é): o mesmo callback deixa os mesmos itens, com os mesmos atributos, nos
     * dois caminhos.
     */
    @Test
    void theApiAndTheWorkerSettleTheSameCallbackIntoTheSameItems() {
        TestRequests.Request byApi = betaRequest();
        worker.apply(sqsEvent(byApi.body()));
        String apiEvent = "bev_" + UUID.randomUUID();
        assertThat(api.receive(BETA, callback(apiEvent, ProviderStub.betaRequestId(byApi.queryId()), null,
                UNREADABLE_OFFER))).isEqualTo("PROCESSED");
        TestRequests.Request byWorker = betaRequest();
        String workerEvent = givenCallbackWaitingForTheWorker(ProviderStub.betaRequestId(byWorker.queryId()),
                UNREADABLE_OFFER);
        worker.apply(sqsEvent(byWorker.body()));

        for (String prefix : List.of("META", "ATTEMPT#", "EVIDENCE#")) {
            assertThat(shapes(queryItems(byApi.queryId(), prefix))).as(prefix)
                    .isEqualTo(shapes(queryItems(byWorker.queryId(), prefix)));
        }
        assertThat(shape(outbox(byApi.queryId()))).isEqualTo(shape(outbox(byWorker.queryId())));
        assertThat(fields(outboxDetail(byApi.queryId()))).isEqualTo(fields(outboxDetail(byWorker.queryId())));
        assertThat(shape(item("CONSUMED#" + byApi.eventId(), "META")))
                .isEqualTo(shape(item("CONSUMED#" + byWorker.eventId(), "META")));
        assertThat(shape(item("INBOX#beta#" + apiEvent, "META")))
                .isEqualTo(shape(item("INBOX#beta#" + workerEvent, "META")));
        assertThat(shape(item("CORR#beta#" + ProviderStub.betaRequestId(byApi.queryId()), "META")))
                .isEqualTo(shape(item("CORR#beta#" + ProviderStub.betaRequestId(byWorker.queryId()), "META")));
        assertThat(queryItems(byApi.queryId(), "ATTEMPT#")).singleElement().satisfies(
                attempt -> assertThat(attribute(attempt, "errorClass")).contains("INVALID_PROVIDER_RESPONSE"));
    }

    @Test
    void redeliveryWithAProviderRequestIdRedoesTheWorkerHalfWithoutCalling() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        givenAcceptedQuery(request, providerRequestId);
        String externalEventId = givenCallbackWaitingForTheWorker(providerRequestId, declinedResult());

        SQSBatchResponse response = worker.apply(sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("CORR#beta#" + providerRequestId, "META"), "queryId")).contains(request.queryId());
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        JsonNode detail = outboxDetail(request.queryId());
        assertThat(detail.path("status").asString()).isEqualTo("DECLINED");
        assertThat(detail.path("reasonCode").asString()).isEqualTo("RISK_NOT_ACCEPTED");
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
        assertInboxProcessed(externalEventId, request.queryId());
    }

    @Test
    void redeliveryWithoutAPendingCallbackOnlyCorrelatesAndNeverCallsAgain() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        givenAcceptedQuery(request, providerRequestId);

        worker.apply(sqsEvent(request.body()));
        worker.apply(sqsEvent(request.body()));

        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("CORR#beta#" + providerRequestId, "META"), "queryId")).contains(request.queryId());
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META")).isEmpty();
    }

    @Test
    void t11_severalPendingCallbacksForTheSameQueryRecordOneResult() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        String first = givenCallbackWaitingForTheWorker(providerRequestId, offerResult());
        String second = givenCallbackWaitingForTheWorker(providerRequestId, offerResult());

        worker.apply(sqsEvent(request.body()));

        assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
        assertThat(outboxDetail(request.queryId()).path("status").asString()).isEqualTo("QUOTED");
        assertInboxProcessed(first, request.queryId());
        assertInboxProcessed(second, request.queryId());
    }

    @Test
    void needsInformationCallbackCarriesTheSnapshotPaths() {
        TestRequests.Request request = betaRequest();
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        givenCallbackWaitingForTheWorker(providerRequestId, """
                {"outcome":"PENDING_INFORMATION","pendingFields":[\
                {"field":"risk.principalDriver.licenseDate","code":"MISSING_LICENSE_DATE"}]}""");

        worker.apply(sqsEvent(request.body()));

        JsonNode detail = outboxDetail(request.queryId());
        assertThat(detail.path("status").asString()).isEqualTo("NEEDS_INFORMATION");
        assertThat(detail.path("reasonCode").asString()).isEqualTo("INSURER_NEEDS_INFORMATION");
        assertThat(detail.path("missingFields").get(0).path("path").asString())
                .isEqualTo("risk.principalDriver.licenseDate");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
    }

    private static TestRequests.Request betaRequest() {
        return TestRequests.insurer("vehicle-hatch-2021", Instant.now().plus(DEADLINE), BETA);
    }

    /**
     * Callback que chegou só com o {@code providerRequestId}, antes da metade do
     * worker: a API o guarda na inbox.
     */
    private String givenCallbackWaitingForTheWorker(String providerRequestId, String result) {
        String externalEventId = "bev_" + UUID.randomUUID();
        assertThat(api.receive(BETA, callback(externalEventId, providerRequestId, null, result)))
                .as("held in the inbox until the worker half").isEqualTo("RECEIVED");
        return externalEventId;
    }

    private static String callback(String externalEventId, String providerRequestId, String clientReference,
            String result) {
        String reference = clientReference == null ? "" : ",\"clientReference\":\"" + clientReference + "\"";
        return """
                {"externalEventId":"%s","providerRequestId":"%s"%s,"sentAt":"%s","result":%s}"""
                .formatted(externalEventId, providerRequestId, reference, Timestamps.format(Instant.now()), result);
    }

    /**
     * Consulta que recebeu o {@code 202} numa entrega que morreu antes da metade do
     * worker no {@code CORR#}.
     */
    private static void givenAcceptedQuery(TestRequests.Request request, String providerRequestId) {
        String startedAt = Timestamps.format(Instant.now().minusSeconds(200));
        Map<String, AttributeValue> query = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "provider", AttributeValue.fromS(BETA),
                "insurerId", AttributeValue.fromS(BETA),
                "workRef", AttributeValue.fromS(request.workRef()),
                "quoteId", AttributeValue.fromS(request.quoteId()),
                "version", AttributeValue.fromN("1"),
                "requestEventId", AttributeValue.fromS(request.eventId())));
        query.put("providerRequestId", AttributeValue.fromS(providerRequestId));
        query.put("gsi2pk", AttributeValue.fromS("QUERY_IN_FLIGHT"));
        query.put("gsi2sk", AttributeValue.fromS(startedAt));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(query));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(startedAt),
                "acceptedAt", AttributeValue.fromS(startedAt),
                "providerRequestId", AttributeValue.fromS(providerRequestId))));
    }

    private static String offerResult() {
        return """
                {"outcome":"OFFER","offerNumber":"BETA-1A2B3C4D","insurerName":"Beta Seguros",\
                "premium":{"total":"2510.00","currency":"BRL"},\
                "paymentOptions":[{"installments":1,"installmentAmount":"2510.00"}],\
                "coverages":[{"code":"COLLISION","description":"Colisão, incêndio e roubo","limit":"75000.00"}],\
                "deductibles":[{"coverage":"COLLISION","amount":"3750.00"}],\
                "expiresAt":"2026-12-01T00:00:00.000Z"}""";
    }

    private static String declinedResult() {
        return "{\"outcome\":\"DECLINED\",\"declineCode\":\"RISK_NOT_ACCEPTED\"}";
    }

    private void assertInboxProcessed(String externalEventId, String queryId) {
        Map<String, AttributeValue> inbox = item("INBOX#beta#" + externalEventId, "META");
        assertThat(attribute(inbox, "processingStatus")).contains("PROCESSED");
        assertThat(attribute(inbox, "queryId")).contains(queryId);
        assertThat(inbox).doesNotContainKeys("gsi2pk", "gsi2sk");
    }

    private JsonNode outboxDetail(String queryId) {
        Map<String, AttributeValue> outbox = outbox(queryId);
        assertThat(attribute(outbox, "detailType")).contains("InsurerResultRecorded");
        return objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
    }

    private static Map<String, AttributeValue> outbox(String queryId) {
        return item("OUTBOX#" + EventIds.insurerResultRecorded(queryId), "META");
    }

    private static List<Map<String, String>> shapes(List<Map<String, AttributeValue>> items) {
        return items.stream().map(InsurerCallbackIT::shape).toList();
    }

    private static Map<String, String> shape(Map<String, AttributeValue> item) {
        assertThat(item).isNotEmpty();
        Map<String, String> shape = new TreeMap<>();
        item.forEach((name, value) -> shape.put(name,
                COMPARED_BY_VALUE.contains(name) ? value.toString() : value.type().name()));
        return shape;
    }

    private static Map<String, String> fields(JsonNode detail) {
        Map<String, String> fields = new TreeMap<>();
        detail.properties().forEach(field -> fields.put(field.getKey(),
                Set.of("status", "reasonCode", "insurerId").contains(field.getKey())
                        ? field.getValue().asString()
                        : field.getValue().getNodeType().name()));
        return fields;
    }
}

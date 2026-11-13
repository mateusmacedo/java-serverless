package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.sqsEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.IssueType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code reconcileWorker} no Floci (desenho, §7 e §8, "Reconciliação manual"):
 * o {@code QueryReconcileRequested}
 * chega à fila como a regra do barramento o entrega, grava o
 * {@code OPERATION#}, reconcilia pelo caminho do
 * reconciler e publica {@code QueryReconciled}. A publicação pela API da quote
 * é do M5.
 */
class ReconcileWorkerIT extends FlociIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration PAST_EVERY_LIVE_DELIVERY = Duration.ofSeconds(60);

    @TestBean
    private Clock clock;

    @Autowired
    private ReconcileWorkerFunction worker;

    @Autowired
    private InsurerWorkerFunction insurerWorker;

    @Autowired
    private ObjectMapper objectMapper;

    static Clock clock() {
        return TestClock.INSTANCE;
    }

    @AfterEach
    void resetClock() {
        TestClock.INSTANCE.reset();
    }

    @Test
    void unknownQueryOfAnInsurerWithStatusApiIsConcludedAndTheResultIsPublishedOnce() {
        TestRequests.Request request = TestRequests.insurer("vehicle-beta-silent", deadline(), "beta");
        insurerWorker.apply(sqsEvent(request.body()));
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        PROVIDERS.betaStatus(providerRequestId, """
                {"providerRequestId":"%s","clientReference":"%s","status":"COMPLETED","receivedAt":"%s",\
                "completedAt":"%s","result":{"outcome":"DECLINED","declineCode":"RISK_NOT_ACCEPTED"}}"""
                .formatted(providerRequestId, request.queryId(), now(), now()));
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);
        String operationId = "op-" + UUID.randomUUID();
        String body = reconcileRequest(operationId, "org-acme", request.queryId());

        SQSBatchResponse response = worker.apply(sqsEvent(body));

        assertThat(response.getBatchItemFailures()).isEmpty();
        Map<String, AttributeValue> operation = item("OPERATION#" + operationId, "META");
        assertThat(attribute(operation, "status")).contains("SUCCEEDED");
        assertThat(attribute(operation, "revision")).contains("2");
        JsonNode reconciled = reconciled(operationId);
        assertThat(reconciled.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(reconciled.path("organizationId").asString()).isEqualTo("org-acme");
        assertThat(reconciled.path("queryId").asString()).isEqualTo(request.queryId());
        assertThat(reconciled.path("revision").asInt()).isEqualTo(2);
        assertThat(reconciled.path("safeSummary").asString()).isNotBlank();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        JsonNode result = objectMapper.readTree(attribute(item("OUTBOX#"
                + EventIds.insurerResultRecorded(request.queryId()), "META"), "detail").orElseThrow());
        assertThat(result.path("status").asString()).isEqualTo("DECLINED");
        assertThat(PROVIDERS.calls(request.queryId())).as("the quote request is never repeated").isEqualTo(1);
        assertThat(PROVIDERS.statusReads(providerRequestId)).isEqualTo(1);

        worker.apply(sqsEvent(body));

        assertThat(item("OPERATION#" + operationId, "META")).as("a redelivery changes nothing").isEqualTo(operation);
        assertThat(PROVIDERS.statusReads(providerRequestId)).isEqualTo(1);
    }

    @Test
    void queryOfAnotherOrganizationIsReportedAsNotFoundAndLeftUntouched() {
        TestRequests.Request request = unknownAlphaQuery();
        Map<String, AttributeValue> before = item("QUERY#" + request.queryId(), "META");
        String operationId = "op-" + UUID.randomUUID();

        worker.apply(sqsEvent(reconcileRequest(operationId, "org-globex", request.queryId())));

        JsonNode reconciled = reconciled(operationId);
        assertThat(reconciled.path("status").asString()).isEqualTo("FAILED");
        assertThat(reconciled.path("safeSummary").asString()).isEqualTo("Consulta não encontrada para a organização");
        assertThat(item("QUERY#" + request.queryId(), "META")).isEqualTo(before);
        assertThat(item("ISSUE#" + Identities.issueId(IssueType.UNKNOWN_QUERY, request.queryId()), "META")).isEmpty();
    }

    @Test
    void unknownQueryWithoutStatusApiFailsTheReconciliationAndKeepsItsIssueOpen() {
        TestRequests.Request request = unknownAlphaQuery();
        String operationId = "op-" + UUID.randomUUID();

        worker.apply(sqsEvent(reconcileRequest(operationId, "org-acme", request.queryId())));

        assertThat(reconciled(operationId).path("status").asString()).isEqualTo("FAILED");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("UNKNOWN");
        assertThat(attribute(item("ISSUE#" + Identities.issueId(IssueType.UNKNOWN_QUERY, request.queryId()), "META"),
                "status")).contains("OPEN");
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
    }

    @Test
    void queryStillWithinItsDeadlineIsNotTouched() {
        TestRequests.Request request = TestRequests.insurer("vehicle-beta-silent", deadline(), "beta");
        insurerWorker.apply(sqsEvent(request.body()));
        String operationId = "op-" + UUID.randomUUID();

        worker.apply(sqsEvent(reconcileRequest(operationId, "org-acme", request.queryId())));

        assertThat(reconciled(operationId).path("status").asString()).isEqualTo("FAILED");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(PROVIDERS.statusReads(ProviderStub.betaRequestId(request.queryId()))).isZero();
    }

    /**
     * Alpha sem resposta depois do envio: o worker deixa a consulta {@code UNKNOWN}
     * para a reconciliação.
     */
    private TestRequests.Request unknownAlphaQuery() {
        TestRequests.Request request = TestRequests.insurer(ProviderStub.VEHICLE_SLOW, deadline());
        insurerWorker.apply(sqsEvent(request.body()));
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("UNKNOWN");
        return request;
    }

    private JsonNode reconciled(String operationId) {
        Map<String, AttributeValue> outbox = item("OUTBOX#" + EventIds.queryReconciled(operationId, 2), "META");
        assertThat(attribute(outbox, "detailType")).contains("QueryReconciled");
        JsonNode detail = objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
        assertThat(detail.path("operationId").asString()).isEqualTo(operationId);
        return detail;
    }

    /**
     * O envelope que a regra {@code QueryReconcileRequested} entrega na fila, com o
     * {@code detail} da quote.
     */
    private String reconcileRequest(String operationId, String organizationId, String queryId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", UUID.randomUUID().toString());
        detail.put("occurredAt", now());
        detail.put("schemaVersion", "1");
        detail.put("operationId", operationId);
        detail.put("organizationId", organizationId);
        detail.put("queryId", queryId);
        detail.put("reason", "UNKNOWN_QUERY");
        detail.put("requestedBy", "operator-ana");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", "0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("detail-type", "QueryReconcileRequested");
        envelope.put("source", "mmda.quote");
        envelope.put("time", now());
        envelope.put("resources", List.of());
        envelope.put("detail", detail);
        return objectMapper.writeValueAsString(envelope);
    }

    private static Instant deadline() {
        return TestClock.INSTANCE.instant().plus(DEADLINE);
    }

    private static String now() {
        return Timestamps.format(Instant.now());
    }
}

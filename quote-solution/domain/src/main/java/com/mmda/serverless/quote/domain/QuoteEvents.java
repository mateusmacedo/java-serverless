package com.mmda.serverless.quote.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eventos da quote-solution (§3 do desenho). Os schemas estão em
 * {@code contracts/quote-solution/asyncapi.yaml}; os {@code detail} são montados só aqui, para a API
 * e as functions gravarem o mesmo formato no outbox.
 */
public final class QuoteEvents {

    public static final String SOURCE = "mmda.quote";
    public static final String PROVIDER_SOURCE = "mmda.provider";
    public static final String SCHEMA_VERSION = "1";

    public static final String QUOTE_ACCEPTED = "QuoteAccepted";
    public static final String ENRICHMENT_REQUESTED = "EnrichmentRequested";
    public static final String INSURER_QUOTE_REQUESTED = "InsurerQuoteRequested";
    public static final String QUOTE_COMPLETED = "QuoteCompleted";

    /** Evento interno do outbox: a relay inicia a execução em vez de publicar no barramento. */
    public static final String START_QUOTE_REQUESTED = "StartQuoteRequested";

    public static final String ENRICHMENT_RESOLVED = "EnrichmentResolved";
    public static final String INSURER_RESULT_RECORDED = "InsurerResultRecorded";
    public static final String OFFER_SELECTED = "OfferSelected";
    public static final String QUERY_RECONCILE_REQUESTED = "QueryReconcileRequested";
    /** Eventos da provider projetados pelo {@code issue-projector} (M5). */
    public static final String QUERY_RECONCILED = "QueryReconciled";
    public static final String OPERATION_ISSUE_RAISED = "OperationIssueRaised";
    public static final String OPERATION_ISSUE_UPDATED = "OperationIssueUpdated";

    public static final String ENRICHMENT_PROVIDER = "enrichment-sim";
    public static final String ENRICHMENT_OPERATION = "VEHICLE_DRIVER_PROFILE";
    public static final String ENRICHMENT_CONTRACT_VERSION = "1";

    private QuoteEvents() {
    }

    /** {@code eventId} do {@code QuoteCompleted}: a consolidação repetida não cria outro evento. */
    public static String completedEventId(String quoteId, int version) {
        return Hashes.uuidV5(quoteId + "|" + version + "|COMPLETED").toString();
    }

    public static Map<String, Object> quoteAccepted(String eventId, Instant occurredAt, String quoteId, int version,
            String organizationId) {
        Map<String, Object> detail = envelope(eventId, occurredAt);
        detail.put("quoteId", quoteId);
        detail.put("version", version);
        detail.put("organizationId", organizationId);
        return detail;
    }

    /** Entrada da execução: a relay usa {@code quoteId}, {@code version} e o nome determinístico. */
    public static Map<String, Object> startQuoteRequested(String eventId, Instant occurredAt, String quoteId,
            int version, String organizationId) {
        Map<String, Object> detail = quoteAccepted(eventId, occurredAt, quoteId, version, organizationId);
        detail.put("executionName", QuoteKeys.executionName(quoteId, version));
        return detail;
    }

    public static Map<String, Object> enrichmentRequested(Instant occurredAt, WorkRef workRef, String organizationId,
            String quoteId, int version, QuoteSnapshot snapshot, Instant deadlineAt) {
        Map<String, Object> detail = envelope(workRef.requestEventId(), occurredAt);
        detail.put("workRef", workRef.value());
        detail.put("organizationId", organizationId);
        detail.put("quoteId", quoteId);
        detail.put("version", version);
        detail.put("provider", ENRICHMENT_PROVIDER);
        detail.put("operation", ENRICHMENT_OPERATION);
        detail.put("contractVersion", ENRICHMENT_CONTRACT_VERSION);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("vehicleRef", snapshot.risk().vehicleRef());
        params.put("principalDriverRef", snapshot.risk().principalDriverRef());
        detail.put("params", params);
        detail.put("deadlineAt", deadlineAt.toString());
        return detail;
    }

    /**
     * Pedido de cotação: leva a cópia autorizada do snapshot e o resumo do enriquecimento, nunca o token
     * nem dado do aplicante.
     */
    public static Map<String, Object> insurerQuoteRequested(Instant occurredAt, WorkRef workRef, String organizationId,
            String quoteId, int version, String insurerId, QuoteSnapshot snapshot, Map<String, Object> enrichment,
            Instant deadlineAt) {
        Map<String, Object> detail = envelope(workRef.requestEventId(), occurredAt);
        detail.put("workRef", workRef.value());
        detail.put("organizationId", organizationId);
        detail.put("quoteId", quoteId);
        detail.put("version", version);
        detail.put("insurerId", insurerId);
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("product", snapshot.product());
        risk.put("usage", snapshot.risk().usage().name());
        risk.put("vehicle", snapshot.risk().vehicle());
        risk.put("principalDriver", snapshot.risk().principalDriver());
        risk.put("coverageProfile", snapshot.coverage());
        risk.put("desiredStartDate", snapshot.desiredStartDate());
        detail.put("risk", risk);
        detail.put("enrichment", enrichment);
        detail.put("deadlineAt", deadlineAt.toString());
        return detail;
    }

    public static Map<String, Object> quoteCompleted(Instant occurredAt, String quoteId, int version, Outcome outcome,
            OutcomeReason outcomeReason, int offerCount) {
        Map<String, Object> detail = envelope(completedEventId(quoteId, version), occurredAt);
        detail.put("quoteId", quoteId);
        detail.put("version", version);
        detail.put("outcome", outcome.name());
        detail.put("outcomeReason", outcomeReason == null ? null : outcomeReason.name());
        detail.put("offerCount", offerCount);
        return detail;
    }

    /** Intenção de proposta registrada; o {@code eventId} é o UUIDv5 do {@code selectionId}. */
    public static Map<String, Object> offerSelected(Instant occurredAt, String quoteId, int version, String offerId,
            String selectionId) {
        Map<String, Object> detail = envelope(Hashes.uuidV5(selectionId).toString(), occurredAt);
        detail.put("quoteId", quoteId);
        detail.put("version", version);
        detail.put("offerId", offerId);
        detail.put("selectionId", selectionId);
        return detail;
    }

    /**
     * Pedido de reconciliação manual de uma consulta da provider. O {@code eventId} é aleatório: a provider
     * deduplica pelo {@code operationId}.
     */
    public static Map<String, Object> queryReconcileRequested(String eventId, Instant occurredAt, String operationId,
            String organizationId, String queryId, String reason, String requestedBy) {
        Map<String, Object> detail = envelope(eventId, occurredAt);
        detail.put("operationId", operationId);
        detail.put("organizationId", organizationId);
        detail.put("queryId", queryId);
        detail.put("reason", reason);
        detail.put("requestedBy", requestedBy);
        return detail;
    }

    private static Map<String, Object> envelope(String eventId, Instant occurredAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", eventId);
        detail.put("occurredAt", occurredAt.toString());
        detail.put("schemaVersion", SCHEMA_VERSION);
        return detail;
    }
}

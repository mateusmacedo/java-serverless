package com.mmda.serverless.provider.functions;

import java.time.Instant;

/**
 * Consulta nova, gravada com {@code attribute_not_exists}; {@code queryKey} só existe no enriquecimento e
 * {@code fencingVersion}, só na consulta do líder dele (sem ela, como no M1, não há liderança).
 * {@code providerOperation} é a operação no protocolo do fornecedor, como {@code VEHICLE_DRIVER_PROFILE}.
 * {@code requestEventId} só existe na seguradora: o {@code CONSUMED} do pedido sai com o resultado do callback.
 */
record QueryDraft(
        String queryId,
        String operation,
        String provider,
        String providerOperation,
        String contractVersion,
        String organizationId,
        String workRef,
        String quoteId,
        int version,
        String insurerId,
        String queryKey,
        Instant deadlineAt,
        Long fencingVersion,
        String requestEventId) {

    static final String ENRICH = "ENRICH";
    static final String QUOTE = "QUOTE";

    QueryDraft(String queryId, String operation, String provider, String providerOperation, String contractVersion,
               String organizationId, String workRef, String quoteId, int version, String insurerId, String queryKey,
               Instant deadlineAt) {
        this(queryId, operation, provider, providerOperation, contractVersion, organizationId, workRef, quoteId, version,
                insurerId, queryKey, deadlineAt, null, null);
    }

    QueryDraft ledBy(long leaderFencingVersion) {
        return new QueryDraft(queryId, operation, provider, providerOperation, contractVersion, organizationId, workRef,
                quoteId, version, insurerId, queryKey, deadlineAt, leaderFencingVersion, requestEventId);
    }

    QueryDraft answering(String eventId) {
        return new QueryDraft(queryId, operation, provider, providerOperation, contractVersion, organizationId, workRef,
                quoteId, version, insurerId, queryKey, deadlineAt, fencingVersion, eventId);
    }
}

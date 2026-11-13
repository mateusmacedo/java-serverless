package com.mmda.serverless.provider.domain.operations;

import java.util.Objects;

/**
 * {@code detail} do {@code QueryReconcileRequested}, publicado pela
 * quote-solution (AsyncAPI da quote). O pedido
 * é deduplicado pelo {@code operationId}. Campos desconhecidos são ignorados na
 * leitura.
 */
public record QueryReconcileRequest(
        String eventId,
        String operationId,
        String organizationId,
        String queryId,
        String reason,
        String requestedBy) {

    public QueryReconcileRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(queryId, "queryId");
    }
}

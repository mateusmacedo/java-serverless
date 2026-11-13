package com.mmda.serverless.provider.domain.operations;

import com.mmda.serverless.provider.domain.EventIds;
import java.util.Objects;

/**
 * {@code detail} do {@code QueryReconciled} (desenho, §3): o resultado de uma
 * reconciliação manual, com
 * {@code eventId} UUIDv5 de {@code operationId|revision}. O {@code revision} é
 * o do {@code OPERATION#} da
 * provider, que conta também o {@code RUNNING}, então o resultado nunca sai com
 * a revisão 1.
 */
public record QueryReconciled(
        String eventId,
        String occurredAt,
        String schemaVersion,
        String operationId,
        String organizationId,
        String queryId,
        ReconcileStatus status,
        String safeSummary,
        long revision) {

    public static final String DETAIL_TYPE = "QueryReconciled";
    public static final String SCHEMA_VERSION = "1";

    public QueryReconciled {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (status == null || status == ReconcileStatus.RUNNING) {
            throw new IllegalArgumentException("A reconciliation result is SUCCEEDED or FAILED");
        }
        if (!eventId.equals(EventIds.queryReconciled(operationId, revision))) {
            throw new IllegalArgumentException("eventId must be the UUIDv5 of operationId|revision");
        }
    }

    public static QueryReconciled of(QueryReconcileRequest request, ReconcileStatus status, String safeSummary,
            long revision, String occurredAt) {
        return new QueryReconciled(EventIds.queryReconciled(request.operationId(), revision), occurredAt,
                SCHEMA_VERSION, request.operationId(), request.organizationId(), request.queryId(), status,
                safeSummary, revision);
    }
}

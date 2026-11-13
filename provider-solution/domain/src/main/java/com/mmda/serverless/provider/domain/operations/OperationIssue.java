package com.mmda.serverless.provider.domain.operations;

import com.mmda.serverless.provider.domain.EventIds;
import java.util.Objects;

/**
 * {@code detail} do {@code OperationIssueRaised} e do {@code OperationIssueUpdated} (desenho, §3). O
 * {@code eventId} é o UUIDv5 de {@code issueId|revision}, e o {@code revision} cresce a cada mudança da
 * ocorrência. {@code organizationId}, {@code queryId} e {@code quoteId} faltam quando o alvo não os tem, como
 * no callback sem correlação.
 */
public record OperationIssue(
        String eventId,
        String occurredAt,
        String schemaVersion,
        String issueId,
        String organizationId,
        IssueType type,
        IssueStatus status,
        String queryId,
        String quoteId,
        String firstSeenAt,
        String lastSeenAt,
        String safeSummary,
        boolean reconcilable,
        long revision) {

    public static final String RAISED = "OperationIssueRaised";
    public static final String UPDATED = "OperationIssueUpdated";
    public static final String SCHEMA_VERSION = "1";

    public OperationIssue {
        Objects.requireNonNull(issueId, "issueId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (revision < 1) {
            throw new IllegalArgumentException("revision starts at 1");
        }
        if (!eventId.equals(EventIds.operationIssue(issueId, revision))) {
            throw new IllegalArgumentException("eventId must be the UUIDv5 of issueId|revision");
        }
    }

    /** Estado gravado no {@code ISSUE#}, de que sai o evento de cada revisão. */
    public record State(String issueId, String organizationId, IssueType type, IssueStatus status, String queryId,
                        String quoteId, String firstSeenAt, String lastSeenAt, String safeSummary, long revision) {

        public OperationIssue event(String occurredAt) {
            return new OperationIssue(EventIds.operationIssue(issueId, revision), occurredAt, SCHEMA_VERSION, issueId,
                    organizationId, type, status, queryId, quoteId, firstSeenAt, lastSeenAt, safeSummary,
                    type.reconcilable() && queryId != null, revision);
        }

        /** Primeira revisão: o evento é {@code OperationIssueRaised}; as seguintes, {@code OperationIssueUpdated}. */
        public String detailType() {
            return revision == 1 ? RAISED : UPDATED;
        }
    }
}

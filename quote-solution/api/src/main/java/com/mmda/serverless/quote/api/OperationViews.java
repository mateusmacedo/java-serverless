package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mmda.serverless.quote.domain.Operations;
import com.mmda.serverless.quote.domain.Permission;
import com.mmda.serverless.quote.domain.QuoteIssues;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Respostas de {@code /v1/operations}: só resumo seguro, sem token, payload nem dado do operador. */
final class OperationViews {

    private OperationViews() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Issue(String id, String queryId, String quoteId, String type, String status, String firstSeenAt,
            String lastSeenAt, String safeSummary, List<String> allowedActions) {

        static Issue of(Map<String, AttributeValue> item, List<Permission> permissions) {
            String queryId = Items.string(item, "queryId");
            String status = Items.string(item, "status");
            AttributeValue reconcilable = item.get("reconcilable");
            return new Issue(Items.string(item, "issueId"), queryId, Items.string(item, "quoteId"),
                    Items.string(item, "issueType"), status, Items.string(item, "firstSeenAt"),
                    Items.string(item, "lastSeenAt"), Items.string(item, "safeSummary"),
                    Operations.issueActions(queryId, reconcilable != null && Boolean.TRUE.equals(reconcilable.bool()),
                            QuoteIssues.Status.OPEN.name().equals(status), permissions));
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record IssuesPage(List<Issue> items, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReconcileAccepted(String operationId, String status, String statusUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Operation(String operationId, String queryId, String status, String safeSummary, String updatedAt) {

        static Operation of(Map<String, AttributeValue> item) {
            return new Operation(Items.string(item, "operationId"), Items.string(item, "queryId"),
                    Items.string(item, "status"), Items.string(item, "safeSummary"), Items.string(item, "updatedAt"));
        }
    }
}

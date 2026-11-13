package com.mmda.serverless.quote.functions;

import com.mmda.serverless.quote.domain.QuoteIssues;
import java.util.Objects;

/**
 * Ocorrência da quote gravada pelo {@code reconciler} no {@code ISSUE#} (§4 do
 * desenho). {@code organizationId}
 * nulo deixa o item fora do {@code gsi1}, como o callback sem correlação da
 * provider; {@code safeSummary} nunca
 * leva token, credencial nem payload.
 */
record QuoteIssue(QuoteIssues.Type type, String target, String organizationId, String quoteId, Integer version,
        String queryId, String safeSummary) {

    QuoteIssue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(safeSummary, "safeSummary");
    }

    String issueId() {
        return QuoteIssues.issueId(type, target);
    }
}

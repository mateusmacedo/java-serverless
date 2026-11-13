package com.mmda.serverless.provider.domain.enrichment;

import com.mmda.serverless.provider.domain.EventIds;
import java.util.List;
import java.util.Objects;

/**
 * {@code detail} do {@code EnrichmentResolved} (desenho, §3). {@code result} só existe em {@code FOUND},
 * {@code notFoundRefs} só em {@code NOT_FOUND} e {@code errorClass} só em {@code FAILED}.
 */
public record EnrichmentResolved(
        String eventId,
        String occurredAt,
        String schemaVersion,
        String workRef,
        String quoteId,
        int version,
        String queryId,
        String evidenceId,
        ReuseDecision reuseDecision,
        EnrichmentResultType resultType,
        EnrichmentProfile result,
        List<String> notFoundRefs,
        String reusableUntil,
        String errorClass) {

    public static final String DETAIL_TYPE = "EnrichmentResolved";
    public static final String SCHEMA_VERSION = "1";

    public EnrichmentResolved {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(reuseDecision, "reuseDecision");
        Objects.requireNonNull(resultType, "resultType");
        if ((resultType == EnrichmentResultType.FOUND) != (result != null)) {
            throw new IllegalArgumentException("result is present only when FOUND");
        }
        if ((resultType == EnrichmentResultType.NOT_FOUND) != (notFoundRefs != null)) {
            throw new IllegalArgumentException("notFoundRefs is present only when NOT_FOUND");
        }
        if ((resultType == EnrichmentResultType.FAILED) != (errorClass != null)) {
            throw new IllegalArgumentException("errorClass is present only when FAILED");
        }
        notFoundRefs = notFoundRefs == null ? null : List.copyOf(notFoundRefs);
    }

    /** Resolução de um pedido; o {@code eventId} é o UUIDv5 de {@code queryId|workRef}. */
    public static EnrichmentResolved of(EnrichmentRequest request, String queryId, String evidenceId,
                                        ReuseDecision reuseDecision, Outcome outcome, String occurredAt) {
        return new EnrichmentResolved(
                EventIds.enrichmentResolved(queryId, request.workRef()),
                occurredAt,
                SCHEMA_VERSION,
                request.workRef(),
                request.quoteId(),
                request.version(),
                queryId,
                evidenceId,
                reuseDecision,
                outcome.resultType(),
                outcome.result(),
                outcome.notFoundRefs(),
                outcome.reusableUntil(),
                outcome.errorClass());
    }

    /** Resultado gravado na evidência, comum ao líder, aos seguidores e ao HIT. */
    public record Outcome(EnrichmentResultType resultType, EnrichmentProfile result, List<String> notFoundRefs,
                          String reusableUntil, String errorClass) {

        public static Outcome found(EnrichmentProfile profile, String reusableUntil) {
            return new Outcome(EnrichmentResultType.FOUND, profile, null, reusableUntil, null);
        }

        public static Outcome notFound(List<String> notFoundRefs, String reusableUntil) {
            return new Outcome(EnrichmentResultType.NOT_FOUND, null, notFoundRefs, reusableUntil, null);
        }

        public static Outcome failed(String errorClass) {
            return new Outcome(EnrichmentResultType.FAILED, null, null, null, errorClass);
        }
    }
}

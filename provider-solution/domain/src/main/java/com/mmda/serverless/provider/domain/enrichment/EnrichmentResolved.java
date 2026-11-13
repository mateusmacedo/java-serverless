package com.mmda.serverless.provider.domain.enrichment;

import com.mmda.serverless.provider.domain.EventIds;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@code detail} do {@code EnrichmentResolved} (desenho, §3). {@code result} só existe em {@code FOUND},
 * {@code notFoundRefs} só em {@code NOT_FOUND}, {@code errorClass} só em {@code FAILED} e {@code reusableUntil}
 * só nos resultados reutilizáveis. {@code policyVersion} é a política de reuso da provider que produziu a
 * evidência, no formato {@link #POLICY_VERSION} do AsyncAPI.
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
        String policyVersion,
        EnrichmentResultType resultType,
        EnrichmentProfile result,
        List<String> notFoundRefs,
        String reusableUntil,
        String errorClass) {

    public static final String DETAIL_TYPE = "EnrichmentResolved";
    public static final String SCHEMA_VERSION = "1";
    public static final Pattern POLICY_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    public EnrichmentResolved {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(reuseDecision, "reuseDecision");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(resultType, "resultType");
        if (!POLICY_VERSION.matcher(policyVersion).matches()) {
            throw new IllegalArgumentException("policyVersion is outside the documented format: " + policyVersion);
        }
        if ((resultType == EnrichmentResultType.FOUND) != (result != null)) {
            throw new IllegalArgumentException("result is present only when FOUND");
        }
        if ((resultType == EnrichmentResultType.NOT_FOUND) != (notFoundRefs != null)) {
            throw new IllegalArgumentException("notFoundRefs is present only when NOT_FOUND");
        }
        if ((resultType == EnrichmentResultType.FAILED) != (errorClass != null)) {
            throw new IllegalArgumentException("errorClass is present only when FAILED");
        }
        if (resultType.isReusable() != (reusableUntil != null)) {
            throw new IllegalArgumentException("reusableUntil is present only in reusable results");
        }
        notFoundRefs = notFoundRefs == null ? null : List.copyOf(notFoundRefs);
    }

    /** Resolução do próprio pedido; o {@code eventId} é o UUIDv5 de {@code queryId|workRef}. */
    public static EnrichmentResolved of(EnrichmentRequest request, String queryId, String evidenceId,
                                        ReuseDecision reuseDecision, String policyVersion, Outcome outcome,
                                        String occurredAt) {
        return of(Recipient.of(request), queryId, evidenceId, reuseDecision, policyVersion, outcome, occurredAt);
    }

    /**
     * Resolução para um destinatário, como cada seguidor no fan-out do líder; {@code queryId} é a consulta que
     * produziu a evidência, e o {@code eventId}, o UUIDv5 de {@code queryId|workRef}.
     */
    public static EnrichmentResolved of(Recipient recipient, String queryId, String evidenceId,
                                        ReuseDecision reuseDecision, String policyVersion, Outcome outcome,
                                        String occurredAt) {
        return new EnrichmentResolved(
                EventIds.enrichmentResolved(queryId, recipient.workRef()),
                occurredAt,
                SCHEMA_VERSION,
                recipient.workRef(),
                recipient.quoteId(),
                recipient.version(),
                queryId,
                evidenceId,
                reuseDecision,
                policyVersion,
                outcome.resultType(),
                outcome.result(),
                outcome.notFoundRefs(),
                outcome.reusableUntil(),
                outcome.errorClass());
    }

    /** Pedido que recebe o resultado: o próprio, ou um seguidor registrado na liderança. */
    public record Recipient(String workRef, String quoteId, int version) {

        public Recipient {
            Objects.requireNonNull(workRef, "workRef");
            Objects.requireNonNull(quoteId, "quoteId");
        }

        public static Recipient of(EnrichmentRequest request) {
            return new Recipient(request.workRef(), request.quoteId(), request.version());
        }
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

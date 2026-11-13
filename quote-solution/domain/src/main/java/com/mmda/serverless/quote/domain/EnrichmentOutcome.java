package com.mmda.serverless.quote.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resultado do enriquecimento obrigatório como a consolidação o enxerga. {@code notFoundRefs} são as
 * referências que o provedor declarou não encontrar, como {@code vehicleRef} (RN07).
 */
public record EnrichmentOutcome(ResultType resultType, List<String> notFoundRefs) {

    /** Referências do {@code EnrichmentRequested} que o provedor pode declarar não encontradas. */
    public static final Set<String> DECLARABLE_REFS = Set.of("vehicleRef", "principalDriverRef");

    public enum ResultType {
        FOUND,
        NOT_FOUND,
        FAILED
    }

    public EnrichmentOutcome {
        Objects.requireNonNull(resultType, "resultType");
        notFoundRefs = notFoundRefs == null ? List.of() : List.copyOf(notFoundRefs);
    }

    public static EnrichmentOutcome found() {
        return new EnrichmentOutcome(ResultType.FOUND, List.of());
    }

    public static EnrichmentOutcome notFound(List<String> notFoundRefs) {
        return new EnrichmentOutcome(ResultType.NOT_FOUND, notFoundRefs);
    }

    public static EnrichmentOutcome failed() {
        return new EnrichmentOutcome(ResultType.FAILED, List.of());
    }

    /** Caminho no snapshot de cada referência não encontrada: {@code vehicleRef} vira {@code risk.vehicleRef}. */
    public List<MissingField> missingFields() {
        return notFoundRefs.stream()
                .map(ref -> new MissingField("risk." + ref, OutcomeReason.ENRICHMENT_NOT_FOUND.name()))
                .toList();
    }
}

package com.mmda.serverless.provider.domain.insurer;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * {@code detail} do {@code InsurerQuoteRequested} (desenho, §3). {@code risk} é
 * a cópia autorizada do
 * snapshot e {@code enrichment} o resumo do enriquecimento; a provider repassa
 * os dois à seguradora sem
 * interpretá-los, então eles ficam como mapas.
 */
public record InsurerQuoteRequest(
        String eventId,
        String workRef,
        String organizationId,
        String quoteId,
        int version,
        String insurerId,
        Map<String, Object> risk,
        Map<String, Object> enrichment,
        Instant deadlineAt) {

    public InsurerQuoteRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(workRef, "workRef");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(insurerId, "insurerId");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
        enrichment = enrichment == null ? Map.of() : enrichment;
    }
}

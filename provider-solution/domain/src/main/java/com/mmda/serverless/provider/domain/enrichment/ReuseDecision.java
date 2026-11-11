package com.mmda.serverless.provider.domain.enrichment;

/** Decisão de reuso registrada em cada {@code EnrichmentResolved}. No M1 toda consulta é {@code MISS}. */
public enum ReuseDecision {
    HIT,
    JOIN,
    MISS
}

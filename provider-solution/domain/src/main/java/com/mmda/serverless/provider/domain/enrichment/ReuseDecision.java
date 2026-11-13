package com.mmda.serverless.provider.domain.enrichment;

/**
 * Decisão de reuso registrada em cada {@code EnrichmentResolved}: {@code HIT} usou a evidência válida do
 * índice, {@code JOIN} esperou a consulta de outro pedido e {@code MISS} teve consulta própria: liderou a chamada
 * ou concluiu sem chamada e sem liderança (prazo vencido ou quota esgotada, sempre {@code FAILED}).
 */
public enum ReuseDecision {
    HIT,
    JOIN,
    MISS
}

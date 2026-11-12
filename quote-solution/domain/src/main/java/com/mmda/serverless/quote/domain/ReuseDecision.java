package com.mmda.serverless.quote.domain;

/**
 * Como a provider obteve a evidência de enriquecimento de um pedido (§3 e §8 do desenho): {@code HIT}
 * reusou uma evidência válida do índice de reuso, {@code JOIN} esperou a consulta de outro pedido em voo e
 * {@code MISS} fez a própria consulta. A política de reuso é da provider; na quote, as três decisões seguem o
 * mesmo fluxo e só ficam registradas na evidência usada pela versão.
 */
public enum ReuseDecision {
    HIT,
    JOIN,
    MISS
}

package com.mmda.serverless.quote.domain;

/** Andamento de uma versão da cotação (PRD §8), independente do resultado. */
public enum Phase {
    RECEIVED,
    VALIDATING,
    ENRICHING,
    QUOTING,
    TERMINAL
}

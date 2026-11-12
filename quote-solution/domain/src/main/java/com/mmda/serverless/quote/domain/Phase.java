package com.mmda.serverless.quote.domain;

import java.util.Arrays;
import java.util.List;

/** Andamento de uma versão da cotação (PRD §8), independente do resultado. */
public enum Phase {
    RECEIVED,
    VALIDATING,
    ENRICHING,
    QUOTING,
    TERMINAL;

    /**
     * Fases que podem estar gravadas quando esta é gravada sem regredir: ela mesma e as anteriores. WHY: as fases
     * só avançam, então o resumo do {@code META} aceita uma fase só sobre estas; uma leitura velha do {@code V#n}
     * não grava uma fase anterior sobre uma posterior (M5).
     */
    public List<Phase> atOrBefore() {
        return Arrays.stream(values()).filter(phase -> phase.ordinal() <= ordinal()).toList();
    }
}

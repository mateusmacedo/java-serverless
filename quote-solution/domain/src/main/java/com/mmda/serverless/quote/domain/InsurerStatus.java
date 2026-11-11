package com.mmda.serverless.quote.domain;

/** Situação do resultado de uma seguradora numa versão (PRD §8). */
public enum InsurerStatus {
    PENDING,
    PROCESSING,
    QUOTED,
    DECLINED,
    NEEDS_INFORMATION,
    UNAVAILABLE,
    TIMED_OUT;

    public boolean isTerminal() {
        return this != PENDING && this != PROCESSING;
    }

    /**
     * Falha técnica na consolidação. WHY: o Map aguarda todos os itens, então um resultado ainda não
     * terminal na consolidação só acontece quando o workflow falhou, e conta como falha técnica.
     */
    public boolean isTechnicalFailure() {
        return this == UNAVAILABLE || this == TIMED_OUT || !isTerminal();
    }
}

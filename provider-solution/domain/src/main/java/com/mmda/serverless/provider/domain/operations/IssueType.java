package com.mmda.serverless.provider.domain.operations;

/**
 * Tipos de ocorrência operacional da provider (desenho, §3). O alvo de cada
 * tipo entra no {@code issueId}: a
 * consulta ({@code UNKNOWN_QUERY}), o callback ({@code UNMATCHED_CALLBACK}), a
 * mensagem descartada
 * ({@code DEAD_LETTER}) ou o evento do outbox ({@code STALE_OUTBOX}).
 */
public enum IssueType {

    UNKNOWN_QUERY(true),
    UNMATCHED_CALLBACK(false),
    DEAD_LETTER(false),
    STALE_OUTBOX(false);

    private final boolean reconcilable;

    IssueType(boolean reconcilable) {
        this.reconcilable = reconcilable;
    }

    /**
     * WHY: só a consulta {@code UNKNOWN} tem o que a reconciliação manual resolve;
     * nos outros, o reparo é automático.
     */
    public boolean reconcilable() {
        return reconcilable;
    }

    /**
     * WHY: a mensagem de um {@code DEAD_LETTER} foi descartada, então nenhuma
     * observação posterior a resolve.
     */
    public boolean selfResolving() {
        return this != DEAD_LETTER;
    }
}

package com.mmda.serverless.quote.domain;

/**
 * Ocorrências operacionais próprias da quote-solution (§3 e §4 do desenho). O {@code reconciler} as grava
 * direto no {@code ISSUE#<issueId>}, sem evento; as da provider chegam por evento no M5.
 */
public final class QuoteIssues {

    /** Valor do atributo {@code source} das ocorrências gravadas pela quote. */
    public static final String SOURCE = "quote";

    private QuoteIssues() {
    }

    public enum Type {
        /** Execução da versão encerrada em {@code FAILED}, {@code TIMED_OUT} ou {@code ABORTED}. */
        WORKFLOW_FAILED,
        /** Waiter {@code EXPIRED} sem o resultado entregue (§8). */
        TASK_EXPIRED,
        /** {@code ACTIVE_VERSION} além de {@code deadlineAt} + {@code stuckVersionMarginSeconds}. */
        STUCK_VERSION,
        /**
         * Mensagem descartada da {@code results-dlq} no limite de tentativas ou sem identificar a cotação
         * (ADR 0007).
         */
        DEAD_LETTER
    }

    public enum Status {
        OPEN,
        RESOLVED
    }

    /**
     * {@code issueId} determinístico pelo tipo e pelo alvo: a mesma situação observada de novo atualiza a
     * mesma ocorrência. WHY: o prefixo {@code quote|} separa estas ocorrências das que a provider gera com o
     * mesmo tipo ({@code DEAD_LETTER}) e o mesmo alvo.
     */
    public static String issueId(Type type, String target) {
        return "iss_" + Hashes.sha256Hex(SOURCE + "|" + type.name() + "|" + target).substring(0, 32);
    }

    /** Alvo das ocorrências de uma versão ({@code WORKFLOW_FAILED} e {@code STUCK_VERSION}). */
    public static String versionTarget(String quoteId, int version) {
        return quoteId + "|" + version;
    }
}

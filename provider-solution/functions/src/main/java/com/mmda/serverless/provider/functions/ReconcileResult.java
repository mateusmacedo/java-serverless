package com.mmda.serverless.provider.functions;

import java.util.List;

/**
 * Resultado de um ciclo do {@code reconciler}. {@code pending}, {@code republished} e {@code failed} são do outbox
 * pendente, como no M1; os demais contam o que cada dever do M4 mudou no ciclo (desenho, §9), e
 * {@code failedDuties} nomeia os deveres que falharam e ficaram para o próximo ciclo.
 */
public record ReconcileResult(
        int pending,
        int republished,
        int failed,
        int staleOutbox,
        int fanOutsResumed,
        int expiredQueries,
        int concludedQueries,
        int unknownQueries,
        int callbacksProcessed,
        int callbacksUnmatched,
        int deadLettersRedriven,
        int deadLettersDiscarded,
        int issuesResolved,
        List<String> failedDuties) {

    public ReconcileResult {
        failedDuties = List.copyOf(failedDuties);
    }
}

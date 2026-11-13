package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.QueryState;
import java.util.Objects;

/**
 * Conclusão de uma consulta numa só transação, com o {@code CONSUMED} do pedido
 * junto do efeito (§8).
 *
 * @param newQuery consulta criada já concluída, quando a provider conclui sem
 *                 chamar; senão {@code null}
 * @param event    evento de resultado, ou {@code null} quando a consulta fica
 *                 para a reconciliação
 */
record Settlement(
        String queryId,
        QueryDraft newQuery,
        QueryState finalState,
        AttemptResult attempt,
        EvidenceDraft evidence,
        OutboxEvent event,
        String consumedEventId,
        String workRef) {

    Settlement {
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(finalState, "finalState");
        if (finalState == QueryState.IN_FLIGHT) {
            throw new IllegalArgumentException("A settlement must leave the query settled");
        }
    }

    /**
     * Tentativa encerrada; {@code httpStatus}, {@code errorClass} e
     * {@code providerRequestId} são opcionais.
     */
    record AttemptResult(int sequence, String outcome, Integer httpStatus, String errorClass,
            String providerRequestId) {
    }

    /** Evento a gravar no outbox; {@code detail} é serializado em JSON. */
    record OutboxEvent(String eventId, String detailType, Object detail) {
    }
}

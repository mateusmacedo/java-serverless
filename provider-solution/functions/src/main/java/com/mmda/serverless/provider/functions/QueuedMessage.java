package com.mmda.serverless.provider.functions;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;

/**
 * Mensagem recebida de uma fila de pedidos, com a tentativa do reprocessamento da DLQ (ADR 0007). WHY: o reenvio
 * adiado leva a tentativa junto; sem ela, a contagem até {@code dlqMaxAttempts} recomeçaria e a mensagem
 * circularia entre a fila e a DLQ até o prazo do pedido.
 */
record QueuedMessage(String body, String dlqAttempt) {

    static QueuedMessage of(SQSEvent.SQSMessage message) {
        SQSEvent.MessageAttribute attempt = message.getMessageAttributes() == null
                ? null
                : message.getMessageAttributes().get(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE);
        return new QueuedMessage(message.getBody(), attempt == null ? null : attempt.getStringValue());
    }
}

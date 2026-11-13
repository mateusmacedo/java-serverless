package com.mmda.serverless.provider.simulators;

import java.time.Instant;
import java.util.Map;

/**
 * Situação de um pedido na seguradora assíncrona beta (§10): o resultado é
 * decidido na chegada e fica pronto em
 * {@code readyAt}, quando o primeiro callback é agendado, mesmo que nenhum
 * callback seja enviado.
 */
record BetaQuote(String providerRequestId, String clientReference, String scenario, Instant receivedAt,
        Instant readyAt, Map<String, Object> result) {

    boolean completedAt(Instant now) {
        return !now.isBefore(readyAt);
    }
}

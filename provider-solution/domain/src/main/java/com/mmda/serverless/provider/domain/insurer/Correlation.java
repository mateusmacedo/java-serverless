package com.mmda.serverless.provider.domain.insurer;

import java.util.Set;

/**
 * Item {@code CORR#<provider>#<providerRequestId>} depois de uma das metades do encontro (§8): o worker grava
 * o {@code queryId} depois do {@code 202}, e a API acrescenta a {@code pendingEvents} o callback que chegou só
 * com o {@code providerRequestId}. INVARIANT: as duas escritas são no mesmo item e devolvem a imagem nova, então
 * quem escreve por último enxerga as duas metades e processa as pendências.
 */
public record Correlation(String queryId, Set<String> pendingEvents) {

    public Correlation {
        pendingEvents = pendingEvents == null ? Set.of() : Set.copyOf(pendingEvents);
    }

    public boolean bothHalves() {
        return queryId != null && !pendingEvents.isEmpty();
    }
}

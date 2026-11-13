package com.mmda.serverless.provider.domain.enrichment;

import java.time.Instant;
import java.util.Objects;

/**
 * Item {@code REUSE#<queryKey>}: aponta para a evidência imutável que um pedido
 * equivalente pode usar sem
 * chamada externa (desenho, §5). {@code revision} cresce a cada troca da
 * evidência apontada, e a escrita é
 * condicionada à revisão lida; {@code policyVersion} é a política de reuso que
 * gravou a entrada.
 */
public record ReuseIndexEntry(
        String queryId,
        String evidenceId,
        EnrichmentResultType resultType,
        Instant reusableUntil,
        long revision,
        String policyVersion) {

    public ReuseIndexEntry {
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(reusableUntil, "reusableUntil");
        if (revision < 1) {
            throw new IllegalArgumentException("revision starts at 1");
        }
    }

    /**
     * Evidência que um pedido pode reutilizar (RN05 e RN06): resultado reutilizável
     * ({@code FOUND} ou
     * {@code NOT_FOUND} declarado), ainda válido e gravado pela política de reuso
     * em vigor. Informação
     * expirada nunca é usada.
     */
    public boolean servesAt(Instant now, String currentPolicyVersion) {
        return resultType.isReusable()
                && now.isBefore(reusableUntil)
                && Objects.equals(policyVersion, currentPolicyVersion);
    }
}

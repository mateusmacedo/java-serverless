package com.mmda.serverless.provider.domain.enrichment;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code detail} do {@code EnrichmentRequested} publicado pela quote-solution (desenho, §3). Campos
 * desconhecidos são ignorados na leitura.
 */
public record EnrichmentRequest(
        String eventId,
        String workRef,
        String organizationId,
        String quoteId,
        int version,
        String provider,
        String operation,
        String contractVersion,
        Params params,
        Instant deadlineAt) {

    public EnrichmentRequest {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(workRef, "workRef");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(quoteId, "quoteId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(deadlineAt, "deadlineAt");
    }

    /** Referências do catálogo que o provedor de enriquecimento resolve. */
    public record Params(String vehicleRef, String principalDriverRef) {

        public Params {
            Objects.requireNonNull(vehicleRef, "vehicleRef");
            Objects.requireNonNull(principalDriverRef, "principalDriverRef");
        }
    }
}

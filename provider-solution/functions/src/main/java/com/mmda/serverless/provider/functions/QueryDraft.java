package com.mmda.serverless.provider.functions;

import java.time.Instant;

/**
 * Consulta nova, gravada com {@code attribute_not_exists}; {@code queryKey} só existe no enriquecimento.
 * Sem liderança (M1), a consulta não tem {@code fencingVersion}.
 *
 * @param providerOperation operação no protocolo do fornecedor, como {@code VEHICLE_DRIVER_PROFILE}
 */
record QueryDraft(
        String queryId,
        String operation,
        String provider,
        String providerOperation,
        String contractVersion,
        String organizationId,
        String workRef,
        String quoteId,
        int version,
        String insurerId,
        String queryKey,
        Instant deadlineAt) {

    static final String ENRICH = "ENRICH";
    static final String QUOTE = "QUOTE";
}

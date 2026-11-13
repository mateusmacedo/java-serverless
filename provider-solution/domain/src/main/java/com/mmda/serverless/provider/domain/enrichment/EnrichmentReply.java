package com.mmda.serverless.provider.domain.enrichment;

import java.util.List;

/**
 * Corpo da resposta do provedor de enriquecimento
 * ({@code POST /sim/enrichment/profiles}), no protocolo
 * dele. Em 200 vêm {@code vehicle}, {@code driver} e {@code observedAt}; em
 * erro, {@code code} e, no
 * {@code NOT_FOUND} declarado, {@code notFoundRefs}.
 */
public record EnrichmentReply(
        String clientReference,
        Vehicle vehicle,
        Driver driver,
        String observedAt,
        String code,
        List<String> notFoundRefs) {

    public record Vehicle(String ref, String make, String model, Integer modelYear, String marketValue) {
    }

    public record Driver(String ref, Integer licenseYears, Integer claimsLast5Years) {
    }
}

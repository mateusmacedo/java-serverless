package com.mmda.serverless.provider.domain.enrichment;

import com.mmda.serverless.provider.domain.Decimals;
import java.util.Objects;

/**
 * Perfil normalizado do veículo e do condutor principal, o {@code result} do {@code EnrichmentResolved}
 * quando {@code FOUND}. É o contrato que a quote usa na seleção de seguradoras (ano do veículo).
 */
public record EnrichmentProfile(Vehicle vehicle, Driver principalDriver) {

    public EnrichmentProfile {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(principalDriver, "principalDriver");
    }

    /** @param marketValue valor de mercado, string decimal em BRL */
    public record Vehicle(String ref, String make, String model, int year, String marketValue) {

        public Vehicle {
            Objects.requireNonNull(ref, "vehicle.ref");
            Decimals.requireDecimal(marketValue, "vehicle.marketValue");
        }
    }

    public record Driver(String ref, int licenseYears, int claimsLast5Years) {

        public Driver {
            Objects.requireNonNull(ref, "principalDriver.ref");
        }
    }
}

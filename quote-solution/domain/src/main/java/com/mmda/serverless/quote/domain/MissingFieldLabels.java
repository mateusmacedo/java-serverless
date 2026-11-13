package com.mmda.serverless.quote.domain;

import java.util.Map;

/**
 * Tabela {@code path → label} dos campos pendentes, exibida pelo frontend sem
 * tradução própria.
 */
public final class MissingFieldLabels {

    private static final Map<String, String> LABELS = Map.of(
            "applicantRef", "Cliente",
            "risk.vehicleRef", "Veículo",
            "risk.usage", "Uso do veículo",
            "risk.principalDriverRef", "Condutor principal",
            "risk.vehicle.year", "Ano do veículo",
            "risk.principalDriver.licenseDate", "Data da primeira habilitação do condutor principal",
            "coverageProfile", "Perfil de cobertura",
            "desiredStartDate", "Início de vigência desejado");

    private MissingFieldLabels() {
    }

    /** Rótulo do campo; caminhos fora da tabela devolvem o próprio caminho. */
    public static String labelOf(String path) {
        return LABELS.getOrDefault(path, path);
    }
}

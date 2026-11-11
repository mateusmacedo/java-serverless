package com.mmda.serverless.provider.domain.insurer;

import java.util.Objects;

/**
 * Pendência de dados apontada pela seguradora.
 *
 * @param path caminho relativo ao snapshot, como {@code risk.principalDriver.licenseDate}
 */
public record MissingField(String path, String reasonCode) {

    public MissingField {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }
}

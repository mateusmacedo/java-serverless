package com.mmda.serverless.quote.domain;

import java.util.Objects;

/**
 * Pendência de dado. O {@code path} é relativo ao snapshot, como {@code risk.principalDriverRef}; o
 * rótulo vem de {@link MissingFieldLabels} na leitura.
 */
public record MissingField(String path, String reasonCode) {

    public MissingField {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }
}

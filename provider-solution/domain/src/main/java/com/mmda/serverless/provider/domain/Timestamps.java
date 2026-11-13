package com.mmda.serverless.provider.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Instantes em ISO-8601 UTC com milissegundos fixos.
 * <p>
 * WHY: o {@code gsi2sk} é comparado como texto; com casas decimais variáveis,
 * como no
 * {@link Instant#toString()}, {@code 10:00:00Z} ficaria depois de
 * {@code 10:00:00.5Z}.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static Instant parse(String value) {
        return Instant.parse(value);
    }
}

package com.mmda.serverless.provider.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Janela fixa da quota de um provedor e credencial, compartilhada por todos os
 * workflows (T21).
 *
 * @param id  início da janela em UTC, no formato {@code yyyyMMdd'T'HHmmss}
 * @param end fim exclusivo, que é o início da próxima janela
 */
public record QuotaWindow(String id, Instant start, Instant end) {

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC);

    public static QuotaWindow of(Instant now, Duration length) {
        long seconds = length.toSeconds();
        if (seconds <= 0) {
            throw new IllegalArgumentException("The quota window must be at least one second");
        }
        Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
        return new QuotaWindow(ID_FORMAT.format(start), start, start.plusSeconds(seconds));
    }

    /** Chave de partição do contador da janela. */
    public String key(String provider, String credentialRef) {
        return "QUOTA#" + provider + "#" + credentialRef + "#" + id;
    }

    /** Segundos inteiros até a virada da janela, arredondados para cima. */
    public long secondsUntilEnd(Instant now) {
        long millis = Duration.between(now, end).toMillis();
        return millis <= 0 ? 0 : (millis + 999) / 1000;
    }
}

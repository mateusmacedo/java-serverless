package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.QuotaWindow;
import java.time.Duration;
import java.time.Instant;

/**
 * Débito de uma chamada na quota do provedor e credencial, na janela corrente
 * (§9). O item da janela
 * expira por TTL uma hora depois da virada.
 */
record QuotaReservation(String key, int limit, QuotaWindow window) {

    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long TTL_AFTER_WINDOW_SECONDS = 3600;

    static QuotaReservation of(ProviderProperties properties, String provider, Instant now) {
        QuotaWindow window = QuotaWindow.of(now, WINDOW);
        return new QuotaReservation(window.key(provider, properties.credentialRef()),
                properties.quotaPerMinute(provider), window);
    }

    long ttlEpochSeconds() {
        return window.end().getEpochSecond() + TTL_AFTER_WINDOW_SECONDS;
    }
}

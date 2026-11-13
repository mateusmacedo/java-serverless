package com.mmda.serverless.provider.domain;

import java.time.Duration;

/**
 * Retry do worker, único dono do retry (desenho, §9), com backoff exponencial e jitter completo.
 * Os tetos dobram a cada retry e somam {@code backoffMax}, então a espera total nunca passa dele.
 *
 * @param retries retries além da tentativa inicial ({@code workerRetries})
 */
public record RetryPolicy(int retries, Duration backoffMax) {

    public RetryPolicy {
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative");
        }
        if (backoffMax.isNegative()) {
            throw new IllegalArgumentException("backoffMax must not be negative");
        }
    }

    public int maxAttempts() {
        return retries + 1;
    }

    /**
     * Espera antes do retry de número {@code retry} (1 é o primeiro retry).
     *
     * @param jitter fração aleatória em [0, 1)
     */
    public Duration backoffBefore(int retry, double jitter) {
        if (retry < 1 || retry > retries) {
            throw new IllegalArgumentException("retry must be between 1 and " + retries);
        }
        long units = (1L << retries) - 1;
        long ceilingMillis = backoffMax.toMillis() * (1L << (retry - 1)) / units;
        double fraction = Math.min(Math.max(jitter, 0.0), 1.0);
        return Duration.ofMillis((long) (ceilingMillis * fraction));
    }
}

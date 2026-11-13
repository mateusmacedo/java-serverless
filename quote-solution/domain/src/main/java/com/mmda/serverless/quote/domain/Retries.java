package com.mmda.serverless.quote.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Repetição com backoff exponencial e jitter completo. WHY: o SDK não repete
 * {@code TransactionConflict} e o Floci não emula o conflito (§9 e §13 do
 * desenho), então o caminho é
 * coberto por teste unitário com um {@link Sleeper} falso.
 */
public final class Retries {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;

    public Retries(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = Objects.requireNonNull(baseDelay);
        this.maxDelay = Objects.requireNonNull(maxDelay);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public static Retries transactionConflicts() {
        return new Retries(4, Duration.ofMillis(50), Duration.ofMillis(800), Sleeper.THREAD);
    }

    /**
     * Executa {@code action}; repete só as falhas aceitas por {@code retryable},
     * até o limite.
     */
    public <T> T run(Supplier<T> action, Predicate<RuntimeException> retryable) {
        for (int attempt = 1;; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException failure) {
                if (attempt >= maxAttempts || !retryable.test(failure)) {
                    throw failure;
                }
                sleeper.sleep(delayFor(attempt));
            }
        }
    }

    private Duration delayFor(int attempt) {
        long ceiling = Math.min(maxDelay.toMillis(), baseDelay.toMillis() << Math.min(attempt - 1, 20));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
    }

    @FunctionalInterface
    public interface Sleeper {

        Sleeper THREAD = duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while backing off", interrupted);
            }
        };

        void sleep(Duration duration);
    }
}

package com.mmda.serverless.provider.domain.operations;

import java.time.Instant;

/**
 * Reprocessamento das DLQs no padrão do ADR 0007 (desenho, §9): abaixo de {@code dlqMaxAttempts}, a mensagem
 * volta à fila de origem, mas só antes do {@code deadlineAt} do pedido; depois do limite ou do prazo, é
 * descartada com uma ocorrência {@code DEAD_LETTER}. A primeira passagem pela fila de origem é a tentativa 1.
 */
public record DeadLetterPolicy(int maxAttempts) {

    public DeadLetterPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("dlqMaxAttempts must be at least 1");
        }
    }

    public sealed interface Decision {
    }

    /** Reenvia à fila de origem como a tentativa {@code nextAttempt}. */
    public record Redrive(int nextAttempt) implements Decision {
    }

    public record Discard(Reason reason) implements Decision {
    }

    public enum Reason {
        MAX_ATTEMPTS,
        DEADLINE_EXCEEDED,
        UNREADABLE
    }

    /**
     * @param attempt    tentativa que terminou na DLQ; 1 para a mensagem original
     * @param deadlineAt prazo do pedido, ou {@code null} quando a mensagem não tem prazo
     */
    public Decision decide(int attempt, Instant deadlineAt, Instant now) {
        if (attempt >= maxAttempts) {
            return new Discard(Reason.MAX_ATTEMPTS);
        }
        if (deadlineAt != null && !now.isBefore(deadlineAt)) {
            return new Discard(Reason.DEADLINE_EXCEEDED);
        }
        return new Redrive(attempt + 1);
    }
}

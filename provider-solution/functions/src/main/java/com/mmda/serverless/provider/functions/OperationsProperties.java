package com.mmda.serverless.provider.functions;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reconciliação, DLQs e métricas (desenho, §9 e §12), pelas variáveis de ambiente do serverless.yml. INVARIANT:
 * {@code inboxStuckAfterSeconds} passa do timeout da {@code api}, então nenhuma invocação dela ainda processa o
 * callback parado; {@code statusInsurers} são as seguradoras com API de situação.
 */
@ConfigurationProperties(prefix = "provider.operations")
public record OperationsProperties(
        @DefaultValue("60") int unmatchedCallbackAfterSeconds,
        @DefaultValue("30") int inboxStuckAfterSeconds,
        @DefaultValue("120") int staleOutboxAfterSeconds,
        @DefaultValue("3") int dlqMaxAttempts,
        @DefaultValue("30") int statusReadBudgetSeconds,
        @DefaultValue("beta") List<String> statusInsurers,
        List<DeadLetterRoute> deadLetterQueues,
        @DefaultValue("mmda/provider-solution") String metricsNamespace,
        @DefaultValue("local") String stage) {

    public OperationsProperties {
        statusInsurers = List.copyOf(statusInsurers);
        deadLetterQueues = deadLetterQueues == null ? List.of() : List.copyOf(deadLetterQueues);
    }

    Duration unmatchedCallbackAfter() {
        return Duration.ofSeconds(unmatchedCallbackAfterSeconds);
    }

    Duration inboxStuckAfter() {
        return Duration.ofSeconds(inboxStuckAfterSeconds);
    }

    Duration staleOutboxAfter() {
        return Duration.ofSeconds(staleOutboxAfterSeconds);
    }

    Duration statusReadBudget() {
        return Duration.ofSeconds(statusReadBudgetSeconds);
    }

    /** @param name nome curto da fila de origem, como {@code insurer-requests}; entra no alvo do {@code DEAD_LETTER} */
    public record DeadLetterRoute(String name, String queueUrl, String deadLetterQueueUrl) {

        boolean configured() {
            return queueUrl != null && !queueUrl.isBlank() && deadLetterQueueUrl != null
                    && !deadLetterQueueUrl.isBlank();
        }
    }
}

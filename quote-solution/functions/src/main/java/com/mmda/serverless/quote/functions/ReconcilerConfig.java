package com.mmda.serverless.quote.functions;

import io.smallrye.config.ConfigMapping;

/** Parâmetros dos deveres do {@code reconciler} no M4 (§9 e §12 do desenho). */
@ConfigMapping(prefix = "reconciler")
public interface ReconcilerConfig {

    /** Fila {@code results}, para onde volta a mensagem reprocessada da DLQ. */
    String resultsQueueUrl();

    String resultsDeadLetterQueueUrl();

    /** Tentativas de uma mensagem da DLQ, contando a entrega original (ADR 0007). */
    int dlqMaxAttempts();

    /** Margem além de {@code deadlineAt} para a ocorrência {@code STUCK_VERSION}. */
    int stuckVersionMarginSeconds();

    /** Idade do {@code RESUMING} a partir da qual o waiter é retomado pelo {@code reconciler} (T13). */
    int resumingRetryAfterSeconds();

    /** Namespace das métricas EMF da solução, o mesmo dos alarmes do {@code serverless.yml}. */
    String metricsNamespace();

    String stage();
}

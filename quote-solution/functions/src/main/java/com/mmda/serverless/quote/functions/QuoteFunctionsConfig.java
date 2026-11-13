package com.mmda.serverless.quote.functions;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quote")
public interface QuoteFunctionsConfig {

    String tableName();

    /** Barramento da plataforma, {@code platform-<stage>-events} (ADR 0013). */
    String eventBusName();

    String stateMachineArn();

    /**
     * Segredo {@code quote-solution/<stage>/token-key}, de onde vem a chave da
     * cifra dos task tokens.
     */
    String tokenKeySecretId();

    /**
     * Limite de seguradoras por versão, parte da invariante de orçamento (§9 do
     * desenho).
     */
    int maxInsurers();

    /** Idade a partir da qual o {@code reconciler} republica um outbox pendente. */
    int outboxRepublishAfterSeconds();

    /**
     * Itens tratados por dever em cada execução do {@code reconciler}: outbox,
     * waiters, versões
     * ({@code ACTIVE_VERSION} e {@code SUMMARY_PENDING}), ocorrências e mensagens
     * das DLQs.
     */
    @WithDefault("50")
    int reconcilerBatchSize();
}

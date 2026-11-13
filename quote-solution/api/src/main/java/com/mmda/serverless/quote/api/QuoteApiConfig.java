package com.mmda.serverless.quote.api;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quote")
public interface QuoteApiConfig {

    String tableName();

    /**
     * Prazo total da versão ({@code quoteDeadlineSeconds}), contado da aceitação
     * durável.
     */
    int deadlineSeconds();

    /** Orientação de polling devolvida enquanto a versão não é terminal. */
    int retryAfterSeconds();

    /** Versão do workflow implantado, registrada em cada versão da cotação. */
    String workflowVersion();

    Auth auth();

    interface Auth {

        /** App client do user pool; tokens de outro client são rejeitados. */
        String clientId();
    }
}

package com.mmda.serverless.provider.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração da API de callbacks, vinda das variáveis de ambiente do serverless.yml. {@code providers} são as
 * seguradoras que entregam resultado por callback; outro provedor no caminho responde {@code 404}.
 */
@ConfigurationProperties(prefix = "callbacks")
public record ApiProperties(String tableName, String payloadsBucket, String callbackSecretId,
                            @DefaultValue("beta") List<String> providers) {

    public ApiProperties {
        providers = List.copyOf(providers);
    }
}

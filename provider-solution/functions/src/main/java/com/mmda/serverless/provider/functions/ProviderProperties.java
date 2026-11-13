package com.mmda.serverless.provider.functions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração das funções, vinda das variáveis de ambiente do serverless.yml (desenho, §12).
 *
 * @param quotaPerMinute       limite de chamadas por minuto, por provedor ({@code enrichment-sim}, {@code alpha},
 *                             {@code beta}), comum a todos os workflows (T21)
 * @param supportedInsurers    seguradoras com rota no simulador, síncronas ou com callback; outra vira
 *                             {@code UNAVAILABLE}
 * @param workerTimeoutSeconds timeout das funções dos workers; limita quanto tempo outra entrega do mesmo pedido
 *                             pode estar viva
 */
@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(
        String tableName,
        String payloadsBucket,
        String eventBusName,
        @DefaultValue("mmda.provider") String eventSource,
        String providersBaseUrl,
        String queryKeySecretId,
        String enrichmentRequestsQueueUrl,
        String insurerRequestsQueueUrl,
        @DefaultValue("5") int httpTimeoutSeconds,
        @DefaultValue("2") int workerRetries,
        @DefaultValue("3") int retryBackoffMaxSeconds,
        @DefaultValue("25") int workerTimeoutSeconds,
        @DefaultValue("900") int enrichmentValiditySeconds,
        @DefaultValue("30") int outboxRepublishAfterSeconds,
        @DefaultValue("default") String credentialRef,
        @DefaultValue("alpha") List<String> supportedInsurers,
        Map<String, Integer> quotaPerMinute) {

    public ProviderProperties {
        quotaPerMinute = quotaPerMinute == null ? Map.of() : Map.copyOf(quotaPerMinute);
        supportedInsurers = List.copyOf(supportedInsurers);
    }

    Duration httpTimeout() {
        return Duration.ofSeconds(httpTimeoutSeconds);
    }

    Duration workerTimeout() {
        return Duration.ofSeconds(workerTimeoutSeconds);
    }

    int quotaPerMinute(String provider) {
        Integer limit = quotaPerMinute.get(provider);
        if (limit == null) {
            throw new IllegalStateException("No quota is configured for provider " + provider);
        }
        return limit;
    }
}

package com.mmda.serverless.provider.simulators;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parâmetros do §12. A tabela é {@code provider-solution-<stage>-simulators},
 * só desta Lambda; a beta agenda
 * os callbacks na fila {@code sim-callbacks}. O atraso do
 * {@code vehicle-beta-late} passa da espera da
 * seguradora na quote.
 */
@ConfigurationProperties(prefix = "simulators")
public record SimulatorProperties(
                String tableName,
                @DefaultValue("1800") int offerValiditySeconds,
                String simCallbacksQueueUrl,
                @DefaultValue("3") int betaCallbackDelaySeconds,
                @DefaultValue("30") int betaLateCallbackDelaySeconds) {
}

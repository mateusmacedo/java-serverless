package com.mmda.serverless.provider.simulators;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param tableName            tabela {@code provider-solution-<stage>-simulators}, só desta Lambda
 * @param offerValiditySeconds validade da oferta simulada ({@code offerValiditySeconds}, §12)
 */
@ConfigurationProperties(prefix = "simulators")
public record SimulatorProperties(String tableName, @DefaultValue("1800") int offerValiditySeconds) {
}

package com.mmda.serverless.provider.functions;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Segredo {@code query-key-secret}, lido uma vez por container. */
@Component
class QueryKeySecret {

    private final SecretsManagerClient secretsManager;
    private final ProviderProperties properties;
    private volatile byte[] value;

    QueryKeySecret(SecretsManagerClient secretsManager, ProviderProperties properties) {
        this.secretsManager = secretsManager;
        this.properties = properties;
    }

    byte[] value() {
        byte[] current = value;
        if (current == null) {
            current = secretsManager.getSecretValue(builder -> builder.secretId(properties.queryKeySecretId()))
                    .secretString()
                    .getBytes(StandardCharsets.UTF_8);
            value = current;
        }
        return current.clone();
    }
}

package com.mmda.serverless.provider.simulators;

import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Segredo {@code callback-secret}, lido uma vez por container, só por quem
 * envia callbacks.
 */
class CallbackSecret {

    private final SecretsManagerClient secretsManager;
    private final String secretId;
    private volatile byte[] value;

    CallbackSecret(SecretsManagerClient secretsManager, String secretId) {
        this.secretsManager = secretsManager;
        this.secretId = secretId;
    }

    byte[] value() {
        byte[] current = value;
        if (current == null) {
            current = secretsManager.getSecretValue(builder -> builder.secretId(secretId))
                    .secretString()
                    .getBytes(StandardCharsets.UTF_8);
            value = current;
        }
        return current.clone();
    }
}

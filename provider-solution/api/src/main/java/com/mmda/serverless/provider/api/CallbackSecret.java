package com.mmda.serverless.provider.api;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Segredo {@code callback-secret}, lido uma vez por container, como os segredos das outras funções. */
@Component
class CallbackSecret {

    private final SecretsManagerClient secretsManager;
    private final ApiProperties properties;
    private volatile byte[] value;

    CallbackSecret(SecretsManagerClient secretsManager, ApiProperties properties) {
        this.secretsManager = secretsManager;
        this.properties = properties;
    }

    byte[] value() {
        byte[] current = value;
        if (current == null) {
            current = secretsManager.getSecretValue(builder -> builder.secretId(properties.callbackSecretId()))
                    .secretString()
                    .getBytes(StandardCharsets.UTF_8);
            value = current;
        }
        return current.clone();
    }
}

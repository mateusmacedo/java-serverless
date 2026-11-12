package com.mmda.serverless.provider.api;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Corpo bruto dos callbacks no bucket de payloads, fora da tabela e dos eventos. WHY: a chave é determinística
 * por consulta e {@code externalEventId}, a mesma que os workers usam, então regravar na reentrega não cria
 * órfão, e o objeto existe antes de a evidência apontar para ele.
 */
@Component
class RawCallbacks {

    private final S3Client s3;
    private final ApiProperties properties;

    RawCallbacks(S3Client s3, ApiProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    String store(String queryId, String externalEventId, String body) {
        String key = "raw/" + queryId + "/callback-" + externalEventId + ".json";
        s3.putObject(builder -> builder
                        .bucket(properties.payloadsBucket())
                        .key(key)
                        .contentType("application/json"),
                RequestBody.fromString(body));
        return key;
    }
}

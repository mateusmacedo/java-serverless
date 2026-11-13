package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Identities;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Respostas brutas dos fornecedores no bucket de payloads da provider, fora da tabela e dos eventos.
 * <p>
 * WHY: a chave é determinística por consulta e tentativa, então regravar na reentrega não cria órfão,
 * e o objeto existe antes de a evidência apontar para ele (PRD, §10).
 */
@Component
class RawResponses {

    private final S3Client s3;
    private final ProviderProperties properties;

    RawResponses(S3Client s3, ProviderProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    /** Grava o corpo e devolve a chave do objeto, que é o {@code rawResponseRef}. */
    String store(String queryId, int attemptSequence, String body) {
        return put("raw/" + queryId + "/" + Identities.sequence(attemptSequence) + ".json", body);
    }

    /** Corpo de um callback; a chave leva o {@code externalEventId}, então cada callback tem o seu objeto. */
    String storeCallback(String queryId, String externalEventId, String body) {
        return put("raw/" + queryId + "/callback-" + externalEventId + ".json", body);
    }

    /** Situação lida pela reconciliação na API de situação; a chave leva a tentativa que ela conclui. */
    String storeStatus(String queryId, int attemptSequence, String body) {
        return put("raw/" + queryId + "/status-" + Identities.sequence(attemptSequence) + ".json", body);
    }

    private String put(String key, String body) {
        s3.putObject(builder -> builder
                        .bucket(properties.payloadsBucket())
                        .key(key)
                        .contentType("application/json"),
                RequestBody.fromString(body));
        return key;
    }
}

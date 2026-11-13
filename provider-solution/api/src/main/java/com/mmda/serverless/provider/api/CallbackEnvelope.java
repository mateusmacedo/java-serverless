package com.mmda.serverless.provider.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mmda.serverless.provider.domain.insurer.InsurerCallback;
import java.util.List;

/**
 * Envelope do callback, lido sem o {@code result}: o que a inbox precisa para guardar o callback e encontrar a
 * consulta. O {@code result} só é lido no processamento, onde um resultado ilegível vira {@code UNAVAILABLE}
 * com {@code INVALID_PROVIDER_RESPONSE}, como na resposta síncrona.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CallbackEnvelope(String externalEventId, String providerRequestId, String clientReference) {

    List<String> problems(boolean hasResult) {
        return InsurerCallback.problems(externalEventId, providerRequestId, clientReference, hasResult);
    }
}

package com.mmda.serverless.provider.simulators;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Mensagem da fila {@code sim-callbacks}: o callback que a seguradora simulada vai enviar depois do atraso. A
 * reentrega do mesmo callback repete o {@code externalEventId}; sem {@code clientReference}, o callback só
 * pode ser associado pelo {@code providerRequestId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CallbackOrder(String provider, String externalEventId, String providerRequestId, String clientReference,
                     Map<String, Object> result) {
}

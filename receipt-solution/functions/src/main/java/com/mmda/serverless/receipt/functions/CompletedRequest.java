package com.mmda.serverless.receipt.functions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Campos do {@code detail} do evento {@code RequestCompleted}, publicado pela sample-solution e
 * descrito em {@code contracts/sample-solution/asyncapi.yaml}.
 * <p>
 * WHY: a receipt-solution só consome o evento; ignorar campos desconhecidos deixa a sample
 * evoluir o payload sem quebrar o consumidor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompletedRequest(String requestId, String signature, Long payloadBytes) {
}

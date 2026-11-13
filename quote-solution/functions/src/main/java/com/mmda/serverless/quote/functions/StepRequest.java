package com.mmda.serverless.quote.functions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code Payload} que a máquina envia à {@code workflow-steps}.
 * {@code insurerId} e {@code outcome} só
 * existem no {@code record-insurer-outcome}; {@code error} é o que o
 * {@code Catch} capturou
 * ({@code Error} e {@code Cause}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepRequest(String step, String quoteId, int version, String insurerId, String outcome, JsonNode error) {

    /**
     * Nome do erro capturado pelo {@code Catch}, sem a causa, que pode trazer
     * detalhe interno.
     */
    String errorName() {
        return error == null || error.get("Error") == null ? null : error.get("Error").asText();
    }
}

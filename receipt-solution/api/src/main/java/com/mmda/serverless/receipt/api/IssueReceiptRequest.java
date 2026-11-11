package com.mmda.serverless.receipt.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Pedido de emissão manual. Tem os mesmos campos que o passo {@code issue} lê do {@code detail}
 * do evento {@code RequestCompleted}.
 */
public record IssueReceiptRequest(
        @NotBlank String requestId,
        @NotBlank String signature,
        @NotNull @Positive Long payloadBytes) {
}

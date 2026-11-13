package com.mmda.serverless.provider.domain.insurer;

import java.util.List;

/**
 * Corpo da resposta de uma seguradora simulada, no protocolo dela
 * ({@code POST /sim/insurers/<id>/quotes}).
 * {@code outcome} é {@code OFFER}, {@code DECLINED} ou
 * {@code PENDING_INFORMATION}; a resposta {@code 202}
 * de uma seguradora assíncrona traz só {@code providerRequestId}.
 */
public record InsurerReply(
        String clientReference,
        String outcome,
        String offerNumber,
        String insurerName,
        Premium premium,
        List<PaymentOption> paymentOptions,
        List<CoverageItem> coverages,
        List<DeductibleItem> deductibles,
        String expiresAt,
        String declineCode,
        List<PendingField> pendingFields,
        String providerRequestId,
        String code) {

    public record Premium(String total, String currency) {
    }

    public record PaymentOption(Integer installments, String installmentAmount) {
    }

    public record CoverageItem(String code, String description, String limit) {
    }

    public record DeductibleItem(String coverage, String amount) {
    }

    public record PendingField(String field, String code) {
    }
}

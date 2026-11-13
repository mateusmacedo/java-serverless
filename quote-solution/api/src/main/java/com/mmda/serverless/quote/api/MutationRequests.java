package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mmda.serverless.quote.domain.CanonicalJson;
import com.mmda.serverless.quote.domain.Idempotency;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Corpos e cabeçalho das mutações do M5: seleção de oferta e pedido de
 * reconciliação. Como na criação, campo
 * desconhecido é 400, porque o {@code requestHash} da idempotência é calculado
 * sobre o body inteiro.
 */
final class MutationRequests {

    static final String OFFER_ID = "offerId";
    static final String REASON = "reason";
    private static final int MAX_OFFER_ID_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 500;

    private MutationRequests() {
    }

    static void requireIdempotencyKey(String idempotencyKey) {
        if (!Idempotency.isValidKey(idempotencyKey)) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError(QuoteResource.IDEMPOTENCY_KEY,
                    "Header obrigatório: até 200 caracteres ASCII visíveis, sem espaços.")));
        }
    }

    static JsonNode json(String body) {
        try {
            return CanonicalJson.parse(body == null ? "" : body);
        } catch (CanonicalJson.InvalidJsonException invalid) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("$", "JSON inválido.")));
        }
    }

    /** {@code {"offerId": "..."}} do {@code POST .../selection}. */
    static String offerId(JsonNode body) {
        return singleText(body, OFFER_ID, MAX_OFFER_ID_LENGTH, "Informe o id de uma oferta da versão.");
    }

    /**
     * {@code {"reason": "..."}} do
     * {@code POST /v1/operations/queries/{id}/reconcile}, registrado na auditoria.
     */
    static String reason(JsonNode body) {
        return singleText(body, REASON, MAX_REASON_LENGTH,
                "Informe o motivo da reconciliação, com até " + MAX_REASON_LENGTH + " caracteres.");
    }

    private static String singleText(JsonNode body, String field, int maxLength, String message) {
        CreateQuoteRequest.requireObject(body);
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of(field).contains(name)) {
                errors.add(new ErrorResponse.FieldError(name, "Campo não reconhecido."));
            }
        });
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            errors.add(new ErrorResponse.FieldError(field, "Campo obrigatório."));
        } else if (!value.isTextual() || value.textValue().isBlank() || characters(value.textValue()) > maxLength) {
            errors.add(new ErrorResponse.FieldError(field, message));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return value.textValue();
    }

    /**
     * WHY: o {@code maxLength} do JSON Schema conta code points; o {@code length()}
     * contaria o emoji duas vezes.
     */
    static int characters(String text) {
        return text.codePointCount(0, text.length());
    }
}

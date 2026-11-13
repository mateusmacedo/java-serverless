package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Corpo do {@code POST /v1/quotes/{id}/versions}: o pedido completo da nova
 * versão, com os campos da criação, e
 * o {@code expectedVersion}, a versão atual que o cliente leu. WHY: o servidor
 * só aceita a nova versão se ela
 * continua atual, então duas edições concorrentes não se sobrescrevem (409
 * {@code VERSION_CONFLICT}).
 */
record CreateVersionRequest(int expectedVersion, CreateQuoteRequest quote) {

    static final String EXPECTED_VERSION = "expectedVersion";
    /**
     * A versão nova é {@code expectedVersion + 1}, e o {@code sk} da versão tem
     * quatro dígitos.
     */
    static final int MAX_EXPECTED_VERSION = 9998;

    static CreateVersionRequest parse(JsonNode body) {
        CreateQuoteRequest.requireObject(body);
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        JsonNode expected = body.get(EXPECTED_VERSION);
        int expectedVersion = 0;
        if (expected == null || expected.isNull()) {
            errors.add(new ErrorResponse.FieldError(EXPECTED_VERSION, "Campo obrigatório."));
        } else if (!expected.isIntegralNumber() || !expected.canConvertToInt() || expected.intValue() < 1
                || expected.intValue() > MAX_EXPECTED_VERSION) {
            errors.add(new ErrorResponse.FieldError(EXPECTED_VERSION,
                    "Informe a versão atual da cotação, um inteiro de 1 a " + MAX_EXPECTED_VERSION + "."));
        } else {
            expectedVersion = expected.intValue();
        }
        CreateQuoteRequest quote = CreateQuoteRequest.parseFields(body, Set.of(EXPECTED_VERSION), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new CreateVersionRequest(expectedVersion, quote);
    }
}

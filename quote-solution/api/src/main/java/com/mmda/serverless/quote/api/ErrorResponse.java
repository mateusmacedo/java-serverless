package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Erro padrão do contrato: {@code code}, {@code message}, {@code correlationId} e {@code fieldErrors}. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String code, String message, String correlationId, List<FieldError> fieldErrors) {

    public record FieldError(String path, String message) {
    }
}

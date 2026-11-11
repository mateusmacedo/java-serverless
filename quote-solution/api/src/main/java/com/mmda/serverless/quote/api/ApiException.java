package com.mmda.serverless.quote.api;

import java.util.List;

/** Erro de negócio da API, respondido no formato do contrato ({@link ErrorResponse}). */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<ErrorResponse.FieldError> fieldErrors;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<ErrorResponse.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public static ApiException validation(List<ErrorResponse.FieldError> fieldErrors) {
        return new ApiException(400, "VALIDATION_ERROR", "A requisição tem campos inválidos.", fieldErrors);
    }

    public static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "Recurso não encontrado.");
    }

    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "O usuário não tem permissão para esta operação.");
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ErrorResponse.FieldError> fieldErrors() {
        return fieldErrors;
    }
}

package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.CallOutcome;

/**
 * Uma chamada a fornecedor: o resultado lido para a classificação e o corpo bruto, que vai para o S3.
 *
 * @param httpStatus status HTTP, ou {@code null} se não houve resposta
 * @param rawBody    corpo bruto, ou {@code null} se não houve resposta
 */
record ProviderExchange<R>(CallOutcome<R> outcome, Integer httpStatus, String rawBody) {

    static <R> ProviderExchange<R> of(CallOutcome<String> raw, EventJson json, Class<R> replyType) {
        return switch (raw) {
            case CallOutcome.Responded<String> responded -> new ProviderExchange<>(
                    new CallOutcome.Responded<>(responded.statusCode(), json.readOrNull(responded.reply(), replyType)),
                    responded.statusCode(),
                    responded.reply());
            case CallOutcome.NotSent<String> notSent ->
                    new ProviderExchange<>(new CallOutcome.NotSent<>(notSent.errorClass()), null, null);
            case CallOutcome.NoResponse<String> noResponse ->
                    new ProviderExchange<>(new CallOutcome.NoResponse<>(noResponse.errorClass()), null, null);
        };
    }

    boolean hasBody() {
        return rawBody != null && !rawBody.isEmpty();
    }
}

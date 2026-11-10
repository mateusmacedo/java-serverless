package com.mmda.serverless.sample.functions;

/**
 * Estado que atravessa os passos da máquina de estado; cada passo devolve o contexto
 * recebido, com os campos que preencheu.
 */
public record RequestContext(
        String requestId,
        String s3Key,
        int attempt,
        String signature,
        Long payloadBytes) {

    RequestContext validated(String payloadSignature, long size) {
        return new RequestContext(requestId, s3Key, attempt, payloadSignature, size);
    }

    RequestContext nextAttempt() {
        return new RequestContext(requestId, s3Key, attempt + 1, null, null);
    }
}

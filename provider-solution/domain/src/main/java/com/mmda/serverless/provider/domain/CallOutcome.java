package com.mmda.serverless.provider.domain;

/**
 * O que o adaptador observou numa chamada HTTP a um fornecedor, antes da
 * classificação por protocolo.
 *
 * @param <R> resposta do fornecedor já lida do corpo
 */
public sealed interface CallOutcome<R> {

    /**
     * O fornecedor respondeu.
     *
     * @param reply corpo lido, ou {@code null} se o corpo não pôde ser lido
     */
    record Responded<R>(int statusCode, R reply) implements CallOutcome<R> {
    }

    /** Falha de conexão antes do envio do pedido: repetir é seguro. */
    record NotSent<R>(String errorClass) implements CallOutcome<R> {
    }

    /**
     * O pedido pode ter sido enviado e a resposta não chegou: o resultado é
     * ambíguo.
     */
    record NoResponse<R>(String errorClass) implements CallOutcome<R> {
    }
}

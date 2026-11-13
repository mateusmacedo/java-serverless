package com.mmda.serverless.quote.api;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Leitura tolerante de atributos: ausente devolve nulo ou o padrão informado.
 */
final class Items {

    private Items() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.s() == null ? null : value.s();
    }

    static long number(Map<String, AttributeValue> item, String name, long fallback) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? fallback : Long.parseLong(value.n());
    }

    /**
     * Id opaco do cliente que pode ser parte de uma chave. WHY: o DynamoDB recusa
     * com {@code ValidationException}
     * a partição acima de 2048 bytes e a ordenação acima de 1024; um id maior que
     * os da plataforma não existe e
     * responde 404 ou 400, sem chegar à tabela.
     */
    static boolean fitsKey(String id) {
        return id != null && id.length() <= MAX_ID_LENGTH;
    }

    static final int MAX_ID_LENGTH = 128;
}

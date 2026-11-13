package com.mmda.serverless.quote.api;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Leitura tolerante de atributos: ausente devolve nulo ou o padrão informado. */
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
}

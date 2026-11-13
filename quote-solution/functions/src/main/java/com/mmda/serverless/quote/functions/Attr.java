package com.mmda.serverless.quote.functions;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Construção e leitura tolerante de atributos do DynamoDB. */
final class Attr {

    private Attr() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static AttributeValue list(List<String> values) {
        return AttributeValue.fromL(values.stream().map(AttributeValue::fromS).toList());
    }

    static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item == null ? null : item.get(name);
        return value == null || value.s() == null ? null : value.s();
    }

    /**
     * Instante ISO-8601; nulo quando falta ou está fora do formato, para um item
     * malformado não parar a varredura.
     */
    static Instant instant(Map<String, AttributeValue> item, String name) {
        String value = string(item, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    static long number(Map<String, AttributeValue> item, String name, long fallback) {
        AttributeValue value = item == null ? null : item.get(name);
        return value == null || value.n() == null ? fallback : Long.parseLong(value.n());
    }

    /**
     * Lista de strings; nula quando o atributo não existe, o que distingue "não
     * rodou" de "vazia".
     */
    static List<String> strings(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item == null ? null : item.get(name);
        if (value == null || !value.hasL()) {
            return null;
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }
}

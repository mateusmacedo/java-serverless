package com.mmda.serverless.quote.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Canonicalização JSON da RFC 8785 (JCS): propriedades ordenadas por unidades
 * UTF-16, sem espaços,
 * strings escapadas como no {@code JSON.stringify} e números no formato do
 * ECMAScript. Base do
 * {@code requestHash} da idempotência, para que a mesma intenção com outra
 * formatação não vire 409.
 */
public final class CanonicalJson {

    // WHY: a RFC 8785 exige rejeitar nomes repetidos; o Jackson ficaria com o
    // último sem avisar. Conteúdo
    // depois do primeiro valor também é rejeitado, senão ficaria fora do hash da
    // idempotência
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private CanonicalJson() {
    }

    /** Lê o JSON com detecção de nomes repetidos. */
    public static JsonNode parse(String json) {
        try {
            JsonNode node = STRICT.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new InvalidJsonException("empty JSON document");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new InvalidJsonException(exception.getOriginalMessage());
        }
    }

    public static String canonicalize(String json) {
        return canonicalize(parse(json));
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        switch (node.getNodeType()) {
            case OBJECT -> writeObject(node, out);
            case ARRAY -> {
                out.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(node.get(i), out);
                }
                out.append(']');
            }
            case STRING -> writeString(node.textValue(), out);
            case NUMBER -> out.append(formatNumber(node.doubleValue()));
            case BOOLEAN -> out.append(node.booleanValue());
            case NULL -> out.append("null");
            default -> throw new InvalidJsonException("unsupported JSON node: " + node.getNodeType());
        }
    }

    private static void writeObject(JsonNode node, StringBuilder out) {
        List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(node.properties());
        // WHY: String.compareTo compara unidades UTF-16, a ordem que a RFC 8785 exige
        properties.sort(Map.Entry.comparingByKey());
        out.append('{');
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(properties.get(i).getKey(), out);
            out.append(':');
            write(properties.get(i).getValue(), out);
        }
        out.append('}');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Número no formato do {@code Number.prototype.toString} do ECMAScript, sobre o
     * double IEEE 754.
     * WHY: desde o JDK 19 o {@code Double.toString} devolve os dígitos mais curtos
     * que identificam o valor,
     * os mesmos que o ECMAScript usa; falta só o formato.
     */
    static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new InvalidJsonException("NaN and Infinity are not valid JSON numbers");
        }
        if (value == 0) {
            return "0";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int k = digits.length();
        int n = k - decimal.scale();
        StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exponent = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(exponent >= 0 ? "+" : "-").append(Math.abs(exponent));
        }
        return out.toString();
    }

    /**
     * JSON malformado, com nome repetido ou com valor que a RFC 8785 não
     * representa.
     */
    public static final class InvalidJsonException extends RuntimeException {

        public InvalidJsonException(String message) {
            super(message);
        }
    }
}

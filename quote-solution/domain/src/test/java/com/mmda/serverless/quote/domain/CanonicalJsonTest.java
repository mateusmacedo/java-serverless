package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Exemplos da RFC 8785 (JCS) e as propriedades de que a idempotência depende.
 */
class CanonicalJsonTest {

        @Test
        void sortsPropertiesRemovesWhitespaceAndKeepsArraysInOrder() {
                String canonical = CanonicalJson.canonicalize("""
                                { "b" : [ 3, 1, {"z": true, "a": null} ],
                                  "a" : "x" }
                                """);

                assertThat(canonical).isEqualTo("{\"a\":\"x\",\"b\":[3,1,{\"a\":null,\"z\":true}]}");
        }

        @Test
        void sortsByUtf16CodeUnitsAsInTheRfcExample() {
                String canonical = CanonicalJson
                                .canonicalize("""
                                                {"\\u20ac":"Euro Sign","\\r":"Carriage Return","\\ufb33":"Hebrew Letter Dalet With Dagesh",
                                                 "1":"One","\\ud83d\\ude00":"Emoji: Grinning Face","\\u0080":"Control","\\u00f6":"Latin Small Letter O With Diaeresis"}
                                                """);

                assertThat(canonical).isEqualTo("{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\","
                                + "\"\u00f6\":\"Latin Small Letter O With Diaeresis\",\"\u20ac\":\"Euro Sign\","
                                + "\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}");
        }

        @Test
        void escapesStringsLikeJsonStringify() {
                assertThat(CanonicalJson.canonicalize("\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\/é\""))
                                .isEqualTo("\"\\u0001\\b\\t\\n\\f\\r\\\"\\\\/é\"");
        }

        @ParameterizedTest
        @CsvSource({
                        "333333333.33333329, 333333333.3333333",
                        "1E30, 1e+30",
                        "4.50, 4.5",
                        "2e-3, 0.002",
                        "0.000000000000000000000000001, 1e-27",
                        "1e21, 1e+21",
                        "1e20, 100000000000000000000",
                        "0.000001, 0.000001",
                        "1e-7, 1e-7",
                        "-0, 0",
                        "42, 42",
                        "-12.5, -12.5",
                        "9007199254740993, 9007199254740992"
        })
        void formatsNumbersAsEcmaScript(String input, String expected) {
                assertThat(CanonicalJson.canonicalize(input)).isEqualTo(expected);
        }

        @Test
        void rejectsDuplicatedPropertyNames() {
                assertThatThrownBy(() -> CanonicalJson.canonicalize("{\"a\":1,\"a\":2}"))
                                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
        }

        @Test
        void rejectsContentAfterTheFirstValue() {
                assertThatThrownBy(() -> CanonicalJson.parse("{\"a\":1} lixo"))
                                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
                assertThatThrownBy(() -> CanonicalJson.parse("{\"a\":1}{\"x\":1}"))
                                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
                assertThat(CanonicalJson.canonicalize("{\"a\":1}\n  ")).isEqualTo("{\"a\":1}");
        }

        @Test
        void rejectsMalformedJson() {
                assertThatThrownBy(() -> CanonicalJson.canonicalize("{\"a\":"))
                                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
                assertThatThrownBy(() -> CanonicalJson.canonicalize(""))
                                .isInstanceOf(CanonicalJson.InvalidJsonException.class);
        }
}

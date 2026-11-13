package com.mmda.serverless.provider.domain;

import java.util.regex.Pattern;

/**
 * Valores monetários trafegam como strings decimais e nunca passam por ponto flutuante binário (RN12). O
 * formato é o dos contratos ({@code ^\d+(\.\d+)?$}): sem sinal, sem expoente e sem separador de milhar.
 */
public final class Decimals {

    private static final Pattern DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    private Decimals() {
    }

    /** Devolve o valor se ele for um decimal válido. */
    public static String requireDecimal(String value, String field) {
        if (!isDecimal(value)) {
            throw new IllegalArgumentException(field + " is not a decimal: " + value);
        }
        return value;
    }

    public static boolean isDecimal(String value) {
        return value != null && DECIMAL.matcher(value).matches();
    }
}

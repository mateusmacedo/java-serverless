package com.mmda.serverless.quote.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Seguradoras simuladas do laboratório (§10 do desenho), na ordem em que a seleção as considera. */
public final class Insurers {

    public static final String ALPHA = "alpha";
    public static final String BETA = "beta";

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    // WHY: o mesmo insurerName que o simulador devolve na oferta; o resultado (gravado na seleção) e a
    // oferta da mesma seguradora precisam mostrar o mesmo nome
    static {
        NAMES.put(ALPHA, "Alpha Seguros");
        NAMES.put(BETA, "Beta Seguros");
    }

    private Insurers() {
    }

    public static List<String> all() {
        return List.copyOf(NAMES.keySet());
    }

    public static Optional<String> nameOf(String insurerId) {
        return Optional.ofNullable(NAMES.get(insurerId));
    }
}

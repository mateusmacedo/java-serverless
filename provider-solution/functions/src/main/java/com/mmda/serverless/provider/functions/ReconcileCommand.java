package com.mmda.serverless.provider.functions;

/** Entrada do agendamento do {@code reconciler}. */
public record ReconcileCommand(Integer maxItems) {

    static final int DEFAULT_MAX_ITEMS = 100;
    private static final int MAX_ITEMS = 500;

    int limit() {
        return maxItems == null ? DEFAULT_MAX_ITEMS : Math.clamp(maxItems, 1, MAX_ITEMS);
    }
}

package com.mmda.serverless.provider.simulators;

/**
 * Contadores dos cenários que dependem de estado entre chamadas, como o
 * {@code vehicle-flaky} (§10).
 */
public interface ScenarioCounters {

    /** Incrementa e devolve quantas chamadas a chave já recebeu, contando esta. */
    long increment(String key);
}

package com.mmda.serverless.provider.functions;

import java.time.Duration;

/** Espera do backoff, separada para os testes não dormirem. */
@FunctionalInterface
interface Sleeper {

    void sleep(Duration duration);
}

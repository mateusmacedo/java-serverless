package com.mmda.serverless.quote.functions;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

@Singleton
class ClockProducer {

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}

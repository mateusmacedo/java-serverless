package com.mmda.serverless.provider.functions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Relógio comum à provider e ao {@link ProviderStub} nos testes de reuso: a
 * validade da evidência e a lease
 * da liderança vencem sem espera real. Sem avanço, é o relógio do sistema.
 */
final class TestClock extends Clock {

    static final TestClock INSTANCE = new TestClock();

    private volatile Duration offset = Duration.ZERO;

    private TestClock() {
    }

    void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (ZoneOffset.UTC.equals(zone)) {
            return this;
        }
        throw new UnsupportedOperationException("The test clock is UTC only");
    }
}

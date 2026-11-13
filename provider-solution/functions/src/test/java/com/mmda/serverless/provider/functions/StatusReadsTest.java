package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Teto das leituras da API de situação num ciclo do {@code reconciler} (desenho, §9). */
class StatusReadsTest {

    private static final Instant START = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void readsStopAtTheBudgetOfTheCycle() {
        Instant until = START.plusSeconds(30);

        assertThat(QueryReconciliation.StatusReads.until(Clock.fixed(START, ZoneOffset.UTC), until).allowed("beta"))
                .isTrue();
        assertThat(QueryReconciliation.StatusReads.until(Clock.fixed(until, ZoneOffset.UTC), until).allowed("beta"))
                .isFalse();
        assertThat(QueryReconciliation.StatusReads.unlimited().allowed("beta")).as("the reconcileWorker").isTrue();
    }

    @Test
    void readsOfAnInsurerThatDidNotAnswerStopUntilTheNextCycle() {
        QueryReconciliation.StatusReads reads =
                QueryReconciliation.StatusReads.until(Clock.fixed(START, ZoneOffset.UTC), START.plusSeconds(30));

        reads.unreachable("beta");

        assertThat(reads.allowed("beta")).isFalse();
        assertThat(reads.allowed("gamma")).isTrue();
    }
}

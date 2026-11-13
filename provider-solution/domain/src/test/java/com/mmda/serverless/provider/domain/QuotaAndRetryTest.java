package com.mmda.serverless.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuotaAndRetryTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void quotaWindowIsTheMinuteOfTheCall() {
        QuotaWindow window = QuotaWindow.of(Instant.parse("2026-11-11T15:42:37.250Z"), MINUTE);

        assertThat(window.id()).isEqualTo("20261111T154200");
        assertThat(window.start()).isEqualTo(Instant.parse("2026-11-11T15:42:00Z"));
        assertThat(window.end()).isEqualTo(Instant.parse("2026-11-11T15:43:00Z"));
        assertThat(window.key("alpha", "default")).isEqualTo("QUOTA#alpha#default#20261111T154200");
    }

    @Test
    void callsInTheSameMinuteShareTheWindow() {
        QuotaWindow first = QuotaWindow.of(Instant.parse("2026-11-11T15:42:00Z"), MINUTE);
        QuotaWindow last = QuotaWindow.of(Instant.parse("2026-11-11T15:42:59.999Z"), MINUTE);
        QuotaWindow next = QuotaWindow.of(Instant.parse("2026-11-11T15:43:00Z"), MINUTE);

        assertThat(last).isEqualTo(first);
        assertThat(next.id()).isEqualTo("20261111T154300");
    }

    @Test
    void secondsUntilTheWindowTurnsAreRoundedUp() {
        QuotaWindow window = QuotaWindow.of(Instant.parse("2026-11-11T15:42:37.250Z"), MINUTE);

        assertThat(window.secondsUntilEnd(Instant.parse("2026-11-11T15:42:37.250Z"))).isEqualTo(23);
        assertThat(window.secondsUntilEnd(Instant.parse("2026-11-11T15:43:05Z"))).isZero();
    }

    @Test
    void retryPolicyAllowsTheInitialAttemptPlusTheRetries() {
        assertThat(new RetryPolicy(2, Duration.ofSeconds(3)).maxAttempts()).isEqualTo(3);
        assertThat(new RetryPolicy(0, Duration.ZERO).maxAttempts()).isEqualTo(1);
    }

    @Test
    void backoffsDoubleAndNeverExceedTheBudget() {
        RetryPolicy policy = new RetryPolicy(2, Duration.ofSeconds(3));

        Duration firstCeiling = policy.backoffBefore(1, 0.9999);
        Duration secondCeiling = policy.backoffBefore(2, 0.9999);

        assertThat(firstCeiling).isLessThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(secondCeiling).isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(firstCeiling.plus(secondCeiling)).isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(policy.backoffBefore(1, 0.0)).isZero();
        assertThat(policy.backoffBefore(2, 0.5)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void backoffRejectsRetriesOutsideThePolicy() {
        RetryPolicy policy = new RetryPolicy(2, Duration.ofSeconds(3));

        assertThatThrownBy(() -> policy.backoffBefore(3, 0.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.backoffBefore(0, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timestampsHaveFixedMillisecondsSoTheyCompareAsText() {
        String whole = Timestamps.format(Instant.parse("2026-11-11T10:00:00Z"));
        String half = Timestamps.format(Instant.parse("2026-11-11T10:00:00.5Z"));

        assertThat(whole).isEqualTo("2026-11-11T10:00:00.000Z");
        assertThat(half).isEqualTo("2026-11-11T10:00:00.500Z");
        assertThat(whole.compareTo(half)).isNegative();
        assertThat(Timestamps.parse(half)).isEqualTo(Instant.parse("2026-11-11T10:00:00.5Z"));
    }

    @Test
    void decimalsAreValidatedWithoutBinaryFloatingPoint() {
        assertThat(Decimals.requireDecimal("2450.10", "premiumTotal")).isEqualTo("2450.10");
        assertThat(Decimals.isDecimal("0.5")).isTrue();
        assertThat(Decimals.isDecimal("1e3")).as("the contract pattern has no exponent").isFalse();
        assertThat(Decimals.isDecimal("1.8E3")).isFalse();
        assertThat(Decimals.isDecimal("-10")).as("amounts have no sign").isFalse();
        assertThat(Decimals.isDecimal("12,50")).isFalse();
        assertThatThrownBy(() -> Decimals.requireDecimal(" ", "premiumTotal"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

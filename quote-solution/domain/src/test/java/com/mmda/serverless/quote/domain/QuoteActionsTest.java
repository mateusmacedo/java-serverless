package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.quote.domain.QuoteActions.QuoteState;
import com.mmda.serverless.quote.domain.QuoteActions.UnavailabilityReason;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteActionsTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final List<Permission> ALL = List.copyOf(EnumSet.allOf(Permission.class));

    @Test
    void offerOfTheCurrentVersionWithoutSelectionIsSelectableUntilItExpires() {
        QuoteState current = new QuoteState(1, 1, false);

        assertThat(QuoteActions.unavailabilityReason(current, NOW.plusSeconds(1), NOW)).isNull();
        assertThat(QuoteActions.unavailabilityReason(current, NOW, NOW)).isEqualTo(UnavailabilityReason.OFFER_EXPIRED);
    }

    @Test
    void unavailabilityFollowsThePrecedenceOrder() {
        Instant expired = NOW.minusSeconds(1);

        assertThat(QuoteActions.unavailabilityReason(new QuoteState(1, 2, true), expired, NOW))
                .isEqualTo(UnavailabilityReason.VERSION_SUPERSEDED);
        assertThat(QuoteActions.unavailabilityReason(new QuoteState(2, 2, true), expired, NOW))
                .isEqualTo(UnavailabilityReason.SELECTION_ALREADY_EXISTS);
    }

    @Test
    void currentVersionWithoutSelectionAllowsActionsByPermission() {
        QuoteState current = new QuoteState(1, 1, false);

        assertThat(QuoteActions.allowedActions(current, ALL, true)).containsExactly("CREATE_VERSION", "SELECT_OFFER");
        assertThat(QuoteActions.allowedActions(current, ALL, false)).containsExactly("CREATE_VERSION");
        assertThat(QuoteActions.allowedActions(current, List.of(Permission.QUOTES_READ), true)).isEmpty();
    }

    @Test
    void selectionOrSupersededVersionRemovesEveryAction() {
        assertThat(QuoteActions.allowedActions(new QuoteState(1, 1, true), ALL, true)).isEmpty();
        assertThat(QuoteActions.allowedActions(new QuoteState(1, 2, false), ALL, true)).isEmpty();
    }
}

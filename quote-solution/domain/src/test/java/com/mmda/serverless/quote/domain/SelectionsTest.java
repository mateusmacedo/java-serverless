package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.quote.domain.Selections.Decision;
import com.mmda.serverless.quote.domain.Selections.Existing;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SelectionsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant VALID = NOW.plusSeconds(600);

    @Test
    void validOfferOfTheCurrentVersionWithoutSelectionIsRecorded() {
        assertThat(Selections.decide(1, 1, null, "of_a", true, VALID, NOW)).isEqualTo(Decision.RECORD);
    }

    @Test
    void t28_sameOfferReturnsTheExistingSelectionAndAnotherOfferIsAConflict() {
        Existing existing = new Existing(1, "of_a");

        assertThat(Selections.decide(1, 1, existing, "of_a", true, VALID, NOW)).isEqualTo(Decision.EXISTING);
        assertThat(Selections.decide(1, 1, existing, "of_b", true, VALID, NOW))
                .isEqualTo(Decision.SELECTION_ALREADY_EXISTS);
    }

    @Test
    void sameOfferIsStillTheExistingSelectionAfterItExpires() {
        assertThat(Selections.decide(1, 1, new Existing(1, "of_a"), "of_a", true, NOW.minusSeconds(1), NOW))
                .as("the intent was recorded while the offer was valid").isEqualTo(Decision.EXISTING);
    }

    @Test
    void t18_expiredOfferIsRejectedAtTheExactInstantToo() {
        assertThat(Selections.decide(1, 1, null, "of_a", true, NOW.minusSeconds(1), NOW))
                .isEqualTo(Decision.OFFER_EXPIRED);
        assertThat(Selections.decide(1, 1, null, "of_a", true, NOW, NOW)).isEqualTo(Decision.OFFER_EXPIRED);
        assertThat(Selections.decide(1, 1, null, "of_a", true, null, NOW)).isEqualTo(Decision.OFFER_EXPIRED);
    }

    @Test
    void supersededVersionComesBeforeTheSelectionAndTheExpiration() {
        assertThat(Selections.decide(1, 2, null, "of_a", true, NOW.minusSeconds(1), NOW))
                .isEqualTo(Decision.VERSION_SUPERSEDED);
        assertThat(Selections.decide(1, 2, new Existing(2, "of_z"), "of_a", true, VALID, NOW))
                .isEqualTo(Decision.VERSION_SUPERSEDED);
        assertThat(Selections.decide(1, 2, null, "of_unknown", false, null, NOW))
                .isEqualTo(Decision.VERSION_SUPERSEDED);
    }

    @Test
    void versionThatDoesNotExistYetAndOfferOutsideTheVersionAreNotFound() {
        assertThat(Selections.decide(3, 2, null, "of_a", true, VALID, NOW)).isEqualTo(Decision.NOT_FOUND);
        assertThat(Selections.decide(2, 2, null, "of_other_org", false, null, NOW)).isEqualTo(Decision.NOT_FOUND);
    }

    @Test
    void selectionIdIsOnePerVersionAndOpaque() {
        String first = QuoteKeys.selectionId("q-1", 1);

        assertThat(first).matches("^sel_[0-9a-f]{32}$").isEqualTo(QuoteKeys.selectionId("q-1", 1));
        assertThat(QuoteKeys.selectionId("q-1", 2)).isNotEqualTo(first);
        assertThat(QuoteEvents.offerSelected(NOW, "q-1", 1, "of_a", first).get("eventId"))
                .isEqualTo(Hashes.uuidV5(first).toString());
    }
}

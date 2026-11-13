package com.mmda.serverless.provider.domain.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReusePlanTest {

        private static final Instant NOW = Instant.parse("2026-11-11T15:00:00Z");
        private static final String POLICY = "reuse-v1";
        private static final String ME = "wr_me";
        private static final String LEADER = "wr_leader";

        @Test
        void validEvidenceOfTheCurrentPolicyIsAHitWithoutLeadership() {
                ReuseIndexEntry entry = reuse(EnrichmentResultType.FOUND, NOW.plusSeconds(1), POLICY);

                assertThat(ReusePlan.of(Optional.of(entry), Optional.empty(), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Hit(entry));
        }

        @Test
        void declaredNotFoundIsReusableButFailureNeverIs() {
                ReuseIndexEntry notFound = reuse(EnrichmentResultType.NOT_FOUND, NOW.plusSeconds(60), POLICY);
                ReuseIndexEntry failed = reuse(EnrichmentResultType.FAILED, NOW.plusSeconds(60), POLICY);

                assertThat(ReusePlan.of(Optional.of(notFound), Optional.empty(), ME, NOW, POLICY))
                                .isInstanceOf(ReusePlan.Hit.class);
                assertThat(ReusePlan.of(Optional.of(failed), Optional.empty(), ME, NOW, POLICY))
                                .isInstanceOf(ReusePlan.Lead.class);
        }

        @Test
        void expiredEvidenceIsNeverUsedAndTheLeadConditionsOnTheRevisionRead() {
                ReuseIndexEntry expired = reuse(EnrichmentResultType.FOUND, NOW, POLICY);

                assertThat(ReusePlan.of(Optional.of(expired), Optional.empty(), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Lead(1, 1, null, 3L));
        }

        @Test
        void evidenceOfAnotherPolicyVersionIsNotReused() {
                ReuseIndexEntry older = reuse(EnrichmentResultType.FOUND, NOW.plusSeconds(600), "reuse-v0");
                ReuseIndexEntry unversioned = reuse(EnrichmentResultType.FOUND, NOW.plusSeconds(600), null);

                assertThat(ReusePlan.of(Optional.of(older), Optional.empty(), ME, NOW, POLICY))
                                .isInstanceOf(ReusePlan.Lead.class);
                assertThat(ReusePlan.of(Optional.of(unversioned), Optional.empty(), ME, NOW, POLICY))
                                .isInstanceOf(ReusePlan.Lead.class);
        }

        @Test
        void withoutLeadershipTheRequestLeadsTheFirstVersion() {
                assertThat(ReusePlan.of(Optional.empty(), Optional.empty(), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Lead(1, 1, null, null));
        }

        @Test
        void leadershipInFlightWithAValidLeaseIsJoined() {
                Leadership inFlight = inFlight(4, 3, NOW.plusSeconds(1));

                assertThat(ReusePlan.of(Optional.empty(), Optional.of(inFlight), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Join(4));
        }

        @Test
        void theLeaseStillHoldsAtItsOwnInstant() {
                Leadership inFlight = inFlight(1, 1, NOW);

                assertThat(inFlight.isTransferableAt(NOW)).isFalse();
                assertThat(inFlight.isTransferableAt(NOW.plusMillis(1))).isTrue();
                assertThat(ReusePlan.of(Optional.empty(), Optional.of(inFlight), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Join(1));
        }

        @Test
        void anExpiredLeaseIsTakenOverWithTheNextVersionAndTheSameChain() {
                Leadership inFlight = inFlight(4, 3, NOW.minusMillis(1));

                assertThat(ReusePlan.of(Optional.empty(), Optional.of(inFlight), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Lead(5, 3, 4L, null));
        }

        @Test
        void aCompletedLeadershipWithoutValidEvidenceStartsANewChain() {
                Leadership completed = completed(4, 3, EnrichmentResultType.FAILED);

                assertThat(ReusePlan.of(Optional.empty(), Optional.of(completed), ME, NOW, POLICY))
                                .isEqualTo(new ReusePlan.Lead(5, 5, 4L, null));
        }

        @Test
        void theRequestNeverFollowsItsOwnLeadership() {
                Leadership mine = new Leadership(Leadership.State.IN_FLIGHT, 2, 1, ME, "q_me", NOW.plusSeconds(30),
                                null,
                                null);

                assertThat(ReusePlan.of(Optional.empty(), Optional.of(mine), ME, NOW, POLICY))
                                .isInstanceOf(ReusePlan.OwnLeadership.class);
        }

        @Test
        void aCompletionAnswersEveryFollowerOfItsChainAndNoOther() {
                Leadership completed = completed(5, 3, EnrichmentResultType.FOUND);

                assertThat(completed.answers(3)).isTrue();
                assertThat(completed.answers(5)).isTrue();
                assertThat(completed.answers(2)).isFalse();
                assertThat(inFlight(5, 3, NOW).answers(4)).isFalse();
        }

        @Test
        void aCompletedLeadershipRecordsItsResult() {
                assertThatThrownBy(
                                () -> new Leadership(Leadership.State.COMPLETED, 1, 1, LEADER, "q_1", NOW, null, null))
                                .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(
                                () -> new Leadership(Leadership.State.IN_FLIGHT, 2, 3, LEADER, "q_1", NOW, null, null))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        private static ReuseIndexEntry reuse(EnrichmentResultType resultType, Instant reusableUntil, String policy) {
                return new ReuseIndexEntry("q_1", "ev_1", resultType, reusableUntil, 3, policy);
        }

        private static Leadership inFlight(long fencingVersion, long firstFencingVersion, Instant leaseUntil) {
                return new Leadership(Leadership.State.IN_FLIGHT, fencingVersion, firstFencingVersion, LEADER,
                                "q_leader",
                                leaseUntil, null, null);
        }

        private static Leadership completed(long fencingVersion, long firstFencingVersion, EnrichmentResultType type) {
                return new Leadership(Leadership.State.COMPLETED, fencingVersion, firstFencingVersion, LEADER,
                                "q_leader",
                                NOW.plusSeconds(30), "ev_leader", type);
        }
}

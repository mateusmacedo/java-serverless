package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.quote.domain.Reconciliation.DeadLetterDecision;
import com.mmda.serverless.quote.domain.Reconciliation.ExecutionState;
import com.mmda.serverless.quote.domain.Reconciliation.ExpiredTaskReview;
import com.mmda.serverless.quote.domain.Reconciliation.VersionAction;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReconciliationTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(120);

    @ParameterizedTest
    @EnumSource(value = ExecutionState.class, names = {"FAILED", "TIMED_OUT", "ABORTED"})
    void t20_executionEndedWithoutConsolidationIsFinalizedEvenBeforeTheDeadline(ExecutionState state) {
        assertThat(Reconciliation.versionAction(state, NOW.plusSeconds(200), MARGIN, NOW))
                .isEqualTo(VersionAction.FINALIZE);
        assertThat(Reconciliation.endedResultReason(state)).isEqualTo("WORKFLOW_" + state.name());
    }

    @ParameterizedTest
    @EnumSource(value = ExecutionState.class, names = {"RUNNING", "SUCCEEDED", "MISSING"})
    void versionWithoutAnEndedExecutionIsOnlyStuckAfterTheDeadlinePlusTheMargin(ExecutionState state) {
        Instant deadline = NOW.minusSeconds(120);

        assertThat(Reconciliation.versionAction(state, deadline, MARGIN, NOW)).isEqualTo(VersionAction.NONE);
        assertThat(Reconciliation.versionAction(state, deadline.minusSeconds(1), MARGIN, NOW))
                .isEqualTo(VersionAction.STUCK);
        assertThat(Reconciliation.versionAction(state, null, MARGIN, NOW)).isEqualTo(VersionAction.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "PENDING_REDRIVE", "SOMETHING_NEW"})
    void unknownOrRunningStatusIsNeverFinalized(String status) {
        ExecutionState state = ExecutionState.of(status);

        assertThat(state).isEqualTo(ExecutionState.RUNNING);
        assertThat(Reconciliation.versionAction(state, NOW.plusSeconds(200), MARGIN, NOW))
                .isEqualTo(VersionAction.NONE);
        assertThat(Reconciliation.versionAction(state, NOW.minusSeconds(200), MARGIN, NOW))
                .as("past the deadline it is only stuck").isEqualTo(VersionAction.STUCK);
    }

    @Test
    void expiredWaiterWithoutARecordedResultIsTheNormalTimeoutAndOpensNoIssue() {
        assertThat(Reconciliation.expiredTask(false, false, true)).isEqualTo(ExpiredTaskReview.LEAVE_INDEX);
        assertThat(Reconciliation.expiredTask(false, false, false)).isEqualTo(ExpiredTaskReview.LEAVE_INDEX);
    }

    @Test
    void recordedResultOpensTaskExpiredOnlyWhenTheEndedExecutionDidNotReceiveIt() {
        assertThat(Reconciliation.expiredTask(true, true, true)).isEqualTo(ExpiredTaskReview.LEAVE_INDEX);
        assertThat(Reconciliation.expiredTask(true, false, true)).isEqualTo(ExpiredTaskReview.OPEN_TASK_EXPIRED);
        assertThat(Reconciliation.expiredTask(true, false, false)).as("the running execution may still use it")
                .isEqualTo(ExpiredTaskReview.WAIT);
    }

    @Test
    void t31_deadLetterIsRedrivenTwiceAndDiscardedOnTheThirdAttempt() {
        assertThat(Reconciliation.deadLetter(1, 3, true)).isEqualTo(DeadLetterDecision.REDRIVE);
        assertThat(Reconciliation.deadLetter(2, 3, true)).isEqualTo(DeadLetterDecision.REDRIVE);
        assertThat(Reconciliation.deadLetter(3, 3, true)).isEqualTo(DeadLetterDecision.DISCARD);
    }

    @Test
    void deadLetterThatDoesNotIdentifyItsResultIsDiscardedAtOnce() {
        assertThat(Reconciliation.deadLetter(1, 3, false)).isEqualTo(DeadLetterDecision.DISCARD);
    }

    @Test
    void issueIdIsStablePerTypeAndTargetAndDiffersFromTheProviderNamespace() {
        String target = QuoteIssues.versionTarget("q-1", 2);

        assertThat(QuoteIssues.issueId(QuoteIssues.Type.STUCK_VERSION, target))
                .matches("^iss_[0-9a-f]{32}$")
                .isEqualTo(QuoteIssues.issueId(QuoteIssues.Type.STUCK_VERSION, "q-1|2"))
                .isNotEqualTo(QuoteIssues.issueId(QuoteIssues.Type.WORKFLOW_FAILED, target));
        String providerHex = Hashes.sha256Hex("STUCK_VERSION|" + target).substring(0, 32);
        assertThat(QuoteIssues.issueId(QuoteIssues.Type.STUCK_VERSION, target))
                .as("the provider builds is_ + sha256(type|target); the quote hash has its own namespace")
                .isNotEqualTo("is_" + providerHex)
                .doesNotContain(providerHex);
    }
}

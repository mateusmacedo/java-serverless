package com.mmda.serverless.provider.domain.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mmda.serverless.provider.domain.Digests;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Regras das ocorrências, da reconciliação manual e do reprocessamento das DLQs (desenho, §3, §5 e §9). */
class OperationsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void reobservingAnOpenIssueChangesNothingUnlessTheProblemRecurs() {
        assertThat(IssueTransition.of(null, IssueStatus.OPEN, false)).isEqualTo(IssueTransition.RAISE);
        assertThat(IssueTransition.of(null, IssueStatus.RESOLVED, false)).isEqualTo(IssueTransition.NONE);
        assertThat(IssueTransition.of(IssueStatus.OPEN, IssueStatus.OPEN, false)).isEqualTo(IssueTransition.NONE);
        assertThat(IssueTransition.of(IssueStatus.OPEN, IssueStatus.OPEN, true)).isEqualTo(IssueTransition.UPDATE);
        assertThat(IssueTransition.of(IssueStatus.OPEN, IssueStatus.RESOLVED, false)).isEqualTo(IssueTransition.UPDATE);
        assertThat(IssueTransition.of(IssueStatus.RESOLVED, IssueStatus.OPEN, false)).isEqualTo(IssueTransition.UPDATE);
        assertThat(IssueTransition.of(IssueStatus.RESOLVED, IssueStatus.RESOLVED, true)).isEqualTo(IssueTransition.NONE);
    }

    @Test
    void issueIdIsDeterministicByTypeAndTarget() {
        String issueId = Identities.issueId(IssueType.UNKNOWN_QUERY, "q_0123");

        assertThat(issueId).matches("^is_[0-9a-f]{32}$")
                .isEqualTo("is_" + Digests.sha256Hex("UNKNOWN_QUERY|q_0123").substring(0, 32))
                .isNotEqualTo(Identities.issueId(IssueType.DEAD_LETTER, "q_0123"));
    }

    @Test
    void eachRevisionOfAnIssueIsItsOwnEventAndOnlyTheFirstIsRaised() {
        OperationIssue.State first = state(1, IssueStatus.OPEN);
        OperationIssue.State resolved = state(2, IssueStatus.RESOLVED);

        assertThat(first.detailType()).isEqualTo(OperationIssue.RAISED);
        assertThat(resolved.detailType()).isEqualTo(OperationIssue.UPDATED);
        OperationIssue event = first.event("2026-11-12T10:00:00.000Z");
        assertThat(event.eventId()).isEqualTo(EventIds.operationIssue(first.issueId(), 1))
                .isNotEqualTo(resolved.event("2026-11-12T10:01:00.000Z").eventId());
        assertThat(event.reconcilable()).isTrue();
        assertThat(event.schemaVersion()).isEqualTo("1");
    }

    @Test
    void onlyAnUnknownQueryWithItsQueryIdIsReconcilable() {
        OperationIssue.State deadLetter = new OperationIssue.State("is_1", "org-acme", IssueType.DEAD_LETTER,
                IssueStatus.OPEN, "q_1", null, "2026-11-12T10:00:00.000Z", "2026-11-12T10:00:00.000Z", "x", 1);
        OperationIssue.State unmatched = new OperationIssue.State("is_2", null, IssueType.UNMATCHED_CALLBACK,
                IssueStatus.OPEN, null, null, "2026-11-12T10:00:00.000Z", "2026-11-12T10:00:00.000Z", "x", 1);

        assertThat(deadLetter.event("2026-11-12T10:00:00.000Z").reconcilable()).isFalse();
        assertThat(unmatched.event("2026-11-12T10:00:00.000Z").reconcilable()).isFalse();
        assertThat(IssueType.DEAD_LETTER.selfResolving()).isFalse();
        assertThat(IssueType.UNMATCHED_CALLBACK.selfResolving()).isTrue();
    }

    @Test
    void queryReconciledCarriesOnlyAResultUnderItsDeterministicEventId() {
        QueryReconcileRequest request = new QueryReconcileRequest("e-1", "op-1", "org-acme", "q_1", "UNKNOWN", "ana");

        QueryReconciled reconciled = QueryReconciled.of(request, ReconcileStatus.SUCCEEDED, "ok", 2,
                "2026-11-12T10:00:00.000Z");

        assertThat(reconciled.eventId()).isEqualTo(EventIds.queryReconciled("op-1", 2));
        assertThatThrownBy(() -> QueryReconciled.of(request, ReconcileStatus.RUNNING, "x", 1, "t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadLetterIsRedrivenBelowTheLimitAndBeforeTheDeadlineOnly() {
        DeadLetterPolicy policy = new DeadLetterPolicy(3);

        assertThat(policy.decide(1, NOW.plusSeconds(60), NOW)).isEqualTo(new DeadLetterPolicy.Redrive(2));
        assertThat(policy.decide(2, null, NOW)).isEqualTo(new DeadLetterPolicy.Redrive(3));
        assertThat(policy.decide(3, NOW.plusSeconds(60), NOW))
                .isEqualTo(new DeadLetterPolicy.Discard(DeadLetterPolicy.Reason.MAX_ATTEMPTS));
        assertThat(policy.decide(1, NOW, NOW))
                .isEqualTo(new DeadLetterPolicy.Discard(DeadLetterPolicy.Reason.DEADLINE_EXCEEDED));
        assertThatThrownBy(() -> new DeadLetterPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static OperationIssue.State state(long revision, IssueStatus status) {
        return new OperationIssue.State(Identities.issueId(IssueType.UNKNOWN_QUERY, "q_1"), "org-acme",
                IssueType.UNKNOWN_QUERY, status, "q_1", "quote-1", "2026-11-12T10:00:00.000Z",
                "2026-11-12T10:00:00.000Z", "Consulta sem resposta", revision);
    }
}

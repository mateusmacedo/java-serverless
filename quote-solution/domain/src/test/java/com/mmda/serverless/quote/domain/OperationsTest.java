package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.quote.domain.Operations.IssueType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OperationsTest {

    private static final List<Permission> RECONCILER = List.of(Permission.OPERATIONS_READ,
            Permission.OPERATIONS_RECONCILE);

    @Test
    void reconcileIsOfferedOnlyForAnOpenReconcilableQueryToWhoCanReconcile() {
        assertThat(Operations.issueActions("q_1", true, true, RECONCILER)).containsExactly("RECONCILE");
        assertThat(Operations.issueActions("q_1", true, true, List.of(Permission.OPERATIONS_READ))).isEmpty();
        assertThat(Operations.issueActions(null, true, true, RECONCILER)).as("callback without a query").isEmpty();
        assertThat(Operations.issueActions("q_1", false, true, RECONCILER)).isEmpty();
        assertThat(Operations.issueActions("q_1", true, false, RECONCILER)).as("already resolved").isEmpty();
    }

    @Test
    void issueTypesCoverBothSolutionsAndOnlyProviderTypesAreProjected() {
        Set<String> quoteTypes = Arrays.stream(QuoteIssues.Type.values()).map(Enum::name).collect(Collectors.toSet());

        assertThat(Arrays.stream(IssueType.values()).map(Enum::name)).containsAll(quoteTypes);
        assertThat(IssueType.isProviderType("UNKNOWN_QUERY")).isTrue();
        assertThat(IssueType.isProviderType("DEAD_LETTER")).isTrue();
        assertThat(IssueType.isProviderType("STUCK_VERSION")).isFalse();
        assertThat(IssueType.isProviderType("SOMETHING_NEW")).isFalse();
    }

    @Test
    void onlyAStuckVersionResolvesItself() {
        assertThat(EnumSet.allOf(QuoteIssues.Type.class)).filteredOn(QuoteIssues.Type::resolvesItself)
                .containsExactly(QuoteIssues.Type.STUCK_VERSION);
    }

    @Test
    void operationIdIsOpaqueAndUnique() {
        String first = Operations.newOperationId();

        assertThat(first).matches("^op_[0-9a-f]{32}$");
        assertThat(Operations.newOperationId()).isNotEqualTo(first);
        assertThat(QuoteKeys.operationStatusUrl(first)).isEqualTo("/v1/operations/" + first);
    }

    @Test
    void phasesOnlyMoveForward() {
        assertThat(Phase.QUOTING.atOrBefore())
                .containsExactly(Phase.RECEIVED, Phase.VALIDATING, Phase.ENRICHING, Phase.QUOTING);
        assertThat(Phase.RECEIVED.atOrBefore()).containsExactly(Phase.RECEIVED);
        assertThat(Phase.TERMINAL.atOrBefore()).containsExactly(Phase.values());
    }
}

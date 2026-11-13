package com.mmda.serverless.provider.domain.insurer;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.provider.domain.CallOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Consulta da situação da beta pela reconciliação (desenho, §9 e §10), sem
 * repetir a chamada de cotação.
 */
class InsurerStatusTest {

        @Test
        void completedStatusIsClassifiedLikeTheCallback() {
                InsurerReply declined = new InsurerReply(null, "DECLINED", null, null, null, null, null, null, null,
                                "RISK_NOT_ACCEPTED", null, null, null);

                InsurerStatus.Verdict verdict = InsurerStatus.classify(new CallOutcome.Responded<>(200,
                                new InsurerStatus("beta-1", "q_1", "COMPLETED", "t0", "t1", declined)));

                assertThat(verdict).isEqualTo(new InsurerStatus.Completed(
                                new InsurerVerdict.Declined("RISK_NOT_ACCEPTED"), "t1"));
        }

        @Test
        void completedStatusWithoutAReadableResultIsUnavailable() {
                InsurerStatus.Verdict verdict = InsurerStatus.classify(new CallOutcome.Responded<>(200,
                                new InsurerStatus("beta-1", "q_1", "COMPLETED", "t0", "t1", null)));

                assertThat(verdict).isInstanceOfSatisfying(InsurerStatus.Completed.class,
                                completed -> assertThat(completed.verdict()).isEqualTo(
                                                new InsurerVerdict.Unavailable("INVALID_PROVIDER_RESPONSE")));
        }

        @Test
        void processingUnknownReferenceAndFailuresNeverConcludeTheQuery() {
                assertThat(InsurerStatus.classify(new CallOutcome.Responded<>(200,
                                new InsurerStatus("beta-1", "q_1", "PROCESSING", "t0", null, null))))
                                .isInstanceOf(InsurerStatus.Pending.class);
                assertThat(InsurerStatus.classify(new CallOutcome.Responded<>(404, null)))
                                .isInstanceOf(InsurerStatus.Unrecognized.class);
                for (CallOutcome<InsurerStatus> failure : List.<CallOutcome<InsurerStatus>>of(
                                new CallOutcome.Responded<>(503, null),
                                new CallOutcome.Responded<>(200, null),
                                new CallOutcome.Responded<>(200,
                                                new InsurerStatus("beta-1", "q_1", "LOST", null, null, null)),
                                new CallOutcome.NotSent<>("CONNECTION_REFUSED"),
                                new CallOutcome.NoResponse<>("READ_TIMEOUT"))) {
                        assertThat(InsurerStatus.classify(failure)).as("%s", failure)
                                        .isInstanceOf(InsurerStatus.Unreachable.class);
                }
        }
}

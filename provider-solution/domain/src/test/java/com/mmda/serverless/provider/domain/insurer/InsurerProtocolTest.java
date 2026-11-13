package com.mmda.serverless.provider.domain.insurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.EventIds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Linhas da seguradora na tabela de classificação do §9 do desenho. */
class InsurerProtocolTest {

        private static final InsurerReply OFFER = new InsurerReply(
                        "q_1", "OFFER", "ALPHA-1", "Alpha Seguros",
                        new InsurerReply.Premium("2450.00", "BRL"),
                        List.of(new InsurerReply.PaymentOption(1, "2450.00"),
                                        new InsurerReply.PaymentOption(10, "245.00")),
                        List.of(new InsurerReply.CoverageItem("COLLISION", "Colisão", "68500.00")),
                        List.of(new InsurerReply.DeductibleItem("COLLISION", "3425.00")),
                        "2026-11-11T15:30:00.000Z", null, null, null, null);

        @Test
        void offerIsQuotedWithTheNormalizedOffer() {
                InsurerVerdict verdict = InsurerProtocol.classify(new CallOutcome.Responded<>(200, OFFER));

                assertThat(verdict).isEqualTo(
                                new InsurerVerdict.Quoted(new Offer("ALPHA-1", "Alpha Seguros", "2450.00", "BRL",
                                                List.of(new Offer.Installment(1, "2450.00"),
                                                                new Offer.Installment(10, "245.00")),
                                                List.of(new Offer.Coverage("COLLISION", "Colisão", "68500.00")),
                                                List.of(new Offer.Deductible("COLLISION", "3425.00")),
                                                "2026-11-11T15:30:00.000Z", null)));
        }

        @Test
        void offerValidityIsNormalizedToUtcWithMilliseconds() {
                InsurerReply offer = offer("2450.00", "BRL", 10, "Colisão", "2026-11-11T12:30:00-03:00");

                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, offer)))
                                .isInstanceOfSatisfying(InsurerVerdict.Quoted.class,
                                                quoted -> assertThat(quoted.offer().validUntil())
                                                                .isEqualTo("2026-11-11T15:30:00.000Z"));
        }

        @Test
        void offerOutsideTheContractFormatsIsUnavailableWithoutRetry() {
                List<InsurerReply> invalid = List.of(
                                offer("2450.00", "BRL", 10, "Colisão", "31/12/2026"),
                                offer("1.8E3", "BRL", 10, "Colisão", "2026-11-11T15:30:00.000Z"),
                                offer("-10", "BRL", 10, "Colisão", "2026-11-11T15:30:00.000Z"),
                                offer("2450.00", "real", 10, "Colisão", "2026-11-11T15:30:00.000Z"),
                                offer("2450.00", "BRL", 0, "Colisão", "2026-11-11T15:30:00.000Z"),
                                offer("2450.00", "BRL", 10, null, "2026-11-11T15:30:00.000Z"));

                for (InsurerReply reply : invalid) {
                        assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, reply)))
                                        .isEqualTo(new InsurerVerdict.Unavailable(
                                                        ErrorClasses.INVALID_PROVIDER_RESPONSE));
                }
        }

        @Test
        void declineIsDeclinedWithTheInsurerReason() {
                InsurerReply decline = reply("DECLINED", "RISK_NOT_ACCEPTED", null);

                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, decline)))
                                .isEqualTo(new InsurerVerdict.Declined("RISK_NOT_ACCEPTED"));
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, reply("DECLINED", null, null))))
                                .isEqualTo(new InsurerVerdict.Declined(InsurerProtocol.DEFAULT_DECLINE_REASON));
        }

        @Test
        void pendingInformationIsNeedsInformationWithSnapshotPaths() {
                InsurerReply pending = reply("PENDING_INFORMATION", null,
                                List.of(new InsurerReply.PendingField("risk.principalDriver.licenseDate",
                                                "MISSING_LICENSE_DATE")));

                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, pending)))
                                .isEqualTo(new InsurerVerdict.NeedsInformation(InsurerProtocol.NEEDS_INFORMATION_REASON,
                                                List.of(new MissingField("risk.principalDriver.licenseDate",
                                                                "MISSING_LICENSE_DATE"))));
        }

        @Test
        void serverErrorsAndConnectionErrorsBeforeSendingAreTransient() {
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(503, null)))
                                .isEqualTo(new InsurerVerdict.Transient("HTTP_503"));
                assertThat(InsurerProtocol.classify(new CallOutcome.NotSent<>("CONNECT_TIMEOUT")))
                                .isEqualTo(new InsurerVerdict.Transient("CONNECT_TIMEOUT"));
        }

        @Test
        void readTimeoutAfterSendingIsUnknown() {
                assertThat(InsurerProtocol.classify(new CallOutcome.NoResponse<>("READ_TIMEOUT")))
                                .isEqualTo(new InsurerVerdict.Unknown(ErrorClasses.PROVIDER_NO_RESPONSE));
        }

        @Test
        void acceptedRequiresTheProviderRequestId() {
                InsurerReply accepted = new InsurerReply("q_1", null, null, null, null, null, null, null, null, null,
                                null,
                                "beta-123", null);

                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(202, accepted)))
                                .isEqualTo(new InsurerVerdict.Accepted("beta-123"));
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(202, null)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
        }

        @Test
        void acceptedWithAProviderRequestIdThatNoCallbackCouldCarryIsUnavailableWithoutRetry() {
                InsurerReply accepted = new InsurerReply("q_1", null, null, null, null, null, null, null, null, null,
                                null,
                                "req:2026/abc", null);

                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(202, accepted)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
        }

        @Test
        void unreadableOrIncompleteAnswersAreUnavailableWithoutRetry() {
                InsurerReply withoutPremium = new InsurerReply("q_1", "OFFER", "ALPHA-1", "Alpha Seguros", null, null,
                                null,
                                null, "2026-11-11T15:30:00.000Z", null, null, null, null);

                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, null)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, withoutPremium)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(200, reply("MAYBE", null, null))))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(400, null)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.PROVIDER_REJECTED_REQUEST));
                assertThat(InsurerProtocol.classify(new CallOutcome.Responded<>(401, null)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.PROVIDER_AUTHENTICATION_FAILED));
        }

        @Test
        void callbackResultIsClassifiedLikeTheSynchronousAnswer() {
                InsurerReply pending = reply("PENDING_INFORMATION", null,
                                List.of(new InsurerReply.PendingField("risk.principalDriver.licenseDate",
                                                "MISSING_LICENSE_DATE")));

                assertThat(InsurerProtocol.classifyCallback(OFFER))
                                .isEqualTo(InsurerProtocol.classify(new CallOutcome.Responded<>(200, OFFER)));
                assertThat(InsurerProtocol.classifyCallback(reply("DECLINED", "RISK_NOT_ACCEPTED", null)))
                                .isEqualTo(new InsurerVerdict.Declined("RISK_NOT_ACCEPTED"));
                assertThat(InsurerProtocol.classifyCallback(pending))
                                .isInstanceOf(InsurerVerdict.NeedsInformation.class);
                assertThat(InsurerProtocol.classifyCallback(null))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
                assertThat(InsurerProtocol.classifyCallback(reply("MAYBE", null, null)))
                                .isEqualTo(new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE));
        }

        @Test
        void terminalVerdictsBecomeOutcomesAndOnlyTheOfferCarriesTheRawResponse() {
                InsurerVerdict quoted = InsurerProtocol.classifyCallback(OFFER);

                InsurerResultRecorded.Outcome offer = InsurerResultRecorded.Outcome.of(quoted, "raw/q_1/callback.json");

                assertThat(offer.status()).isEqualTo(InsurerResultStatus.QUOTED);
                assertThat(offer.offer().rawResponseRef()).isEqualTo("raw/q_1/callback.json");
                assertThat(offer.evidenceBody()).isEqualTo(offer.offer());
                assertThat(InsurerResultRecorded.Outcome.of(new InsurerVerdict.Declined("RISK_NOT_ACCEPTED"), "raw")
                                .evidenceBody())
                                .isEqualTo(Map.of("reasonCode", "RISK_NOT_ACCEPTED"));
                assertThat(InsurerResultRecorded.Outcome
                                .of(new InsurerVerdict.Unavailable("INVALID_PROVIDER_RESPONSE"), "raw")
                                .status()).isEqualTo(InsurerResultStatus.UNAVAILABLE);
                assertThatThrownBy(() -> InsurerResultRecorded.Outcome.of(new InsurerVerdict.Accepted("beta-1"), null))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void eventFromTheRecordedQueryEqualsTheEventFromTheRequest() {
                InsurerQuoteRequest request = new InsurerQuoteRequest("evt-1", "wr_1", "org-acme", "quote-1", 2, "beta",
                                Map.of(), Map.of(), Instant.parse("2026-11-11T15:05:00Z"));
                InsurerResultRecorded.Outcome outcome = InsurerResultRecorded.Outcome.declined("RISK_NOT_ACCEPTED");

                assertThat(InsurerResultRecorded.of(new InsurerResultRecorded.Origin("wr_1", "quote-1", 2, "beta"),
                                "q_1",
                                "q_1#0001", outcome, "2026-11-11T15:00:00.000Z"))
                                .isEqualTo(InsurerResultRecorded.of(request, "q_1", "q_1#0001", outcome,
                                                "2026-11-11T15:00:00.000Z"));
        }

        @Test
        void recordedEventIsKeyedByTheQueryAndCarriesOnlyItsStatusFields() {
                InsurerQuoteRequest request = new InsurerQuoteRequest("evt-1", "wr_1", "org-acme", "quote-1", 1,
                                "alpha",
                                Map.of("usage", "PERSONAL"), null, Instant.parse("2026-11-11T15:05:00Z"));

                InsurerResultRecorded declined = InsurerResultRecorded.of(request, "q_1", "q_1#0001",
                                InsurerResultRecorded.Outcome.declined("RISK_NOT_ACCEPTED"),
                                "2026-11-11T15:00:00.000Z");

                assertThat(declined.eventId()).isEqualTo(EventIds.insurerResultRecorded("q_1"));
                assertThat(declined.offer()).isNull();
                assertThat(declined.missingFields()).isNull();
                assertThat(request.enrichment()).isEmpty();
                assertThatThrownBy(() -> InsurerResultRecorded.of(request, "q_1", null,
                                InsurerResultRecorded.Outcome.unavailable(null), "2026-11-11T15:00:00.000Z"))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        private static InsurerReply offer(String total, String currency, int installments, String coverageLabel,
                        String expiresAt) {
                return new InsurerReply("q_1", "OFFER", "ALPHA-1", "Alpha Seguros",
                                new InsurerReply.Premium(total, currency),
                                List.of(new InsurerReply.PaymentOption(installments, "245.00")),
                                List.of(new InsurerReply.CoverageItem("COLLISION", coverageLabel, "68500.00")),
                                List.of(new InsurerReply.DeductibleItem("COLLISION", "3425.00")),
                                expiresAt, null, null, null, null);
        }

        private static InsurerReply reply(String outcome, String declineCode, List<InsurerReply.PendingField> pending) {
                return new InsurerReply("q_1", outcome, null, null, null, null, null, null, null, declineCode, pending,
                                null, null);
        }
}

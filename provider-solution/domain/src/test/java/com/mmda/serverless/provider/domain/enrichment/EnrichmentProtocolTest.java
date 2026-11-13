package com.mmda.serverless.provider.domain.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.ErrorClasses;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Linhas do provedor de enriquecimento na tabela de classificação do §9 do
 * desenho.
 */
class EnrichmentProtocolTest {

        private static final EnrichmentReply PROFILE = new EnrichmentReply(
                        "q_1",
                        new EnrichmentReply.Vehicle("vehicle-1", "Fiat", "Argo", 2019, "68500.00"),
                        new EnrichmentReply.Driver("driver-1", 8, 0),
                        "2026-11-11T15:00:00.000Z",
                        null,
                        null);

        @Test
        void okIsFoundWithTheNormalizedProfile() {
                EnrichmentVerdict verdict = EnrichmentProtocol.classify(new CallOutcome.Responded<>(200, PROFILE));

                assertThat(verdict).isEqualTo(new EnrichmentVerdict.Found(new EnrichmentProfile(
                                new EnrichmentProfile.Vehicle("vehicle-1", "Fiat", "Argo", 2019, "68500.00"),
                                new EnrichmentProfile.Driver("driver-1", 8, 0))));
        }

        @ParameterizedTest
        @ValueSource(strings = { "1.8E3", "-68500.00", "68.500,00" })
        void marketValueOutsideTheContractFormatIsAnInvalidResponse(String marketValue) {
                EnrichmentReply reply = new EnrichmentReply("q_1",
                                new EnrichmentReply.Vehicle("vehicle-1", "Fiat", "Argo", 2019, marketValue),
                                new EnrichmentReply.Driver("driver-1", 8, 0), "2026-11-11T15:00:00.000Z", null, null);

                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(200, reply)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.INVALID_PROVIDER_RESPONSE));
        }

        @Test
        void notFoundDeclaredByTheProviderIsNotFound() {
                EnrichmentReply declared = new EnrichmentReply(null, null, null, null, "NOT_FOUND",
                                List.of("vehicleRef"));

                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(404, declared)))
                                .isEqualTo(new EnrichmentVerdict.NotFound(List.of("vehicleRef")));
        }

        @Test
        void undeclaredNotFoundIsFailedNeverNotFound() {
                EnrichmentReply withoutRefs = new EnrichmentReply(null, null, null, null, "NOT_FOUND", List.of());
                EnrichmentReply unknownRef = new EnrichmentReply(null, null, null, null, "NOT_FOUND", List.of("cpf"));

                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(404, null)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.UNDECLARED_NOT_FOUND));
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(404, withoutRefs)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.UNDECLARED_NOT_FOUND));
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(404, unknownRef)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.UNDECLARED_NOT_FOUND));
        }

        @ParameterizedTest
        @ValueSource(ints = { 500, 502, 503, 504, 429 })
        void serverErrorsAreTransient(int status) {
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(status, null)))
                                .isEqualTo(new EnrichmentVerdict.Transient("HTTP_" + status));
        }

        @Test
        void connectionErrorBeforeSendingIsTransient() {
                assertThat(EnrichmentProtocol.classify(new CallOutcome.NotSent<>("CONNECTION_REFUSED")))
                                .isEqualTo(new EnrichmentVerdict.Transient("CONNECTION_REFUSED"));
        }

        @Test
        void authenticationAndInvalidPayloadAreFailedNeverNotFound() {
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(401, null)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.PROVIDER_AUTHENTICATION_FAILED));
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(403, null)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.PROVIDER_AUTHENTICATION_FAILED));
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(400, null)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.INVALID_PAYLOAD));
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(409, null)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.PROVIDER_REJECTED_REQUEST));
        }

        @Test
        void readTimeoutAfterSendingIsUnknown() {
                assertThat(EnrichmentProtocol.classify(new CallOutcome.NoResponse<>("READ_TIMEOUT")))
                                .isEqualTo(new EnrichmentVerdict.Unknown(ErrorClasses.PROVIDER_NO_RESPONSE));
        }

        @Test
        void incompleteProfileIsAnInvalidResponse() {
                EnrichmentReply withoutYear = new EnrichmentReply("q_1",
                                new EnrichmentReply.Vehicle("vehicle-1", "Fiat", "Argo", null, "68500.00"),
                                PROFILE.driver(), null, null, null);

                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(200, withoutYear)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.INVALID_PROVIDER_RESPONSE));
                assertThat(EnrichmentProtocol.classify(new CallOutcome.Responded<>(200, null)))
                                .isEqualTo(new EnrichmentVerdict.Failed(ErrorClasses.INVALID_PROVIDER_RESPONSE));
        }

        @Test
        void resolvedEventCarriesOnlyTheFieldsOfItsResultType() {
                EnrichmentRequest request = new EnrichmentRequest("evt-1", "wr_1", "org-acme", "quote-1", 1,
                                "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                                new EnrichmentRequest.Params("vehicle-1", "driver-1"),
                                Instant.parse("2026-11-11T15:05:00Z"));

                EnrichmentResolved failed = EnrichmentResolved.of(request, "q_1", "ev_1", ReuseDecision.MISS,
                                "reuse-v1",
                                EnrichmentResolved.Outcome.failed(ErrorClasses.PROVIDER_UNAVAILABLE),
                                "2026-11-11T15:00:00.000Z");

                assertThat(failed.resultType()).isEqualTo(EnrichmentResultType.FAILED);
                assertThat(failed.result()).isNull();
                assertThat(failed.notFoundRefs()).isNull();
                assertThat(failed.errorClass()).isEqualTo(ErrorClasses.PROVIDER_UNAVAILABLE);
                assertThatThrownBy(() -> new EnrichmentResolved("e", "t", "1", "wr", "q", 1, "q_1", "ev",
                                ReuseDecision.MISS,
                                "reuse-v1", EnrichmentResultType.FOUND, null, null, null, null))
                                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void resolvedEventRejectsMissingReferencesAndFieldsIncoherentWithTheResultType() {
                EnrichmentRequest request = new EnrichmentRequest("evt-1", "wr_1", "org-acme", "quote-1", 1,
                                "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                                new EnrichmentRequest.Params("vehicle-1", "driver-1"),
                                Instant.parse("2026-11-11T15:05:00Z"));
                EnrichmentProfile profile = new EnrichmentProfile(
                                new EnrichmentProfile.Vehicle("vehicle-1", "Fiat", "Argo", 2019, "68500.00"),
                                new EnrichmentProfile.Driver("driver-1", 8, 0));
                String occurredAt = "2026-11-11T15:00:00.000Z";
                String reusableUntil = "2026-11-11T15:15:00.000Z";

                assertThatThrownBy(() -> EnrichmentResolved.of(request, "q_1", null, ReuseDecision.HIT, "reuse-v1",
                                EnrichmentResolved.Outcome.found(profile, reusableUntil), occurredAt))
                                .as("evidenceId").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> EnrichmentResolved.of(request, null, "ev_1", ReuseDecision.JOIN, "reuse-v1",
                                EnrichmentResolved.Outcome.found(profile, reusableUntil), occurredAt))
                                .as("queryId").isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> EnrichmentResolved.of(request, "q_1", "ev_1", ReuseDecision.MISS, "reuse-v1",
                                EnrichmentResolved.Outcome.found(profile, null), occurredAt))
                                .as("FOUND without reusableUntil").isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> EnrichmentResolved.of(request, "q_1", "ev_1", ReuseDecision.MISS, "reuse-v1",
                                EnrichmentResolved.Outcome.notFound(List.of("vehicleRef"), null), occurredAt))
                                .as("NOT_FOUND without reusableUntil").isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> EnrichmentResolved.of(request, "q_1", "ev_1", ReuseDecision.MISS, "reuse-v1",
                                new EnrichmentResolved.Outcome(EnrichmentResultType.FAILED, null, null, reusableUntil,
                                                ErrorClasses.PROVIDER_UNAVAILABLE),
                                occurredAt))
                                .as("FAILED is never reusable").isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> EnrichmentResolved.of(request, "q_1", "ev_1", ReuseDecision.MISS, "reuse v1",
                                EnrichmentResolved.Outcome.found(profile, reusableUntil), occurredAt))
                                .as("policyVersion outside the documented format")
                                .isInstanceOf(IllegalArgumentException.class);
        }
}

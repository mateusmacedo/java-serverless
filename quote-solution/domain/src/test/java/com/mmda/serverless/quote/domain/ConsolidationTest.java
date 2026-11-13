package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.quote.domain.Consolidation.ConsolidationResult;
import com.mmda.serverless.quote.domain.Consolidation.InsurerResultView;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Precedência completa do PRD §8, linha a linha, e os casos-limite que a
 * reconciliação encontra.
 */
class ConsolidationTest {

        private static final EligibilityResult ELIGIBLE = new EligibilityResult(EligibilityDecision.ELIGIBLE, null,
                        List.of(), EligibilityRule.VERSION);
        private static final List<String> BOTH = List.of(Insurers.ALPHA, Insurers.BETA);

        private static InsurerResultView result(String insurerId, InsurerStatus status, MissingField... missing) {
                return new InsurerResultView(insurerId, status, List.of(missing));
        }

        private static ConsolidationResult insurers(InsurerResultView... results) {
                return Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.found(), BOTH, List.of(results));
        }

        @Nested
        class BeforeTheInsurers {

                @Test
                void t24_incompleteInputIsNeedsInformationWithTheEligibilityMissingFields() {
                        EligibilityResult incomplete = new EligibilityResult(EligibilityDecision.NEEDS_INFORMATION,
                                        OutcomeReason.MISSING_LICENSE_DATE,
                                        List.of(new MissingField("risk.principalDriverRef", "MISSING_LICENSE_DATE")),
                                        EligibilityRule.VERSION);

                        ConsolidationResult result = Consolidation.consolidate(incomplete, null, null, List.of());

                        assertThat(result.outcome()).isEqualTo(Outcome.NEEDS_INFORMATION);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.MISSING_LICENSE_DATE);
                        assertThat(result.missingFields())
                                        .containsExactly(new MissingField("risk.principalDriverRef",
                                                        "MISSING_LICENSE_DATE"));
                }

                @Test
                void t23_ownRuleRefusalIsIneligible() {
                        EligibilityResult refused = new EligibilityResult(EligibilityDecision.INELIGIBLE,
                                        OutcomeReason.SALVAGE_VEHICLE, List.of(), EligibilityRule.VERSION);

                        ConsolidationResult result = Consolidation.consolidate(refused, null, null, List.of());

                        assertThat(result.outcome()).isEqualTo(Outcome.INELIGIBLE);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.SALVAGE_VEHICLE);
                        assertThat(result.missingFields()).isEmpty();
                }

                @Test
                void eligibilityDecidesBeforeAnyLaterStepEvenWhenTheyRecordedSomething() {
                        EligibilityResult refused = new EligibilityResult(EligibilityDecision.INELIGIBLE,
                                        OutcomeReason.SALVAGE_VEHICLE, List.of(), EligibilityRule.VERSION);

                        ConsolidationResult result = Consolidation.consolidate(refused, EnrichmentOutcome.found(), BOTH,
                                        List.of(result(Insurers.ALPHA, InsurerStatus.QUOTED)));

                        assertThat(result.outcome()).isEqualTo(Outcome.INELIGIBLE);
                }

                @Test
                void t25_enrichmentNotFoundIsNeedsInformationPointingTheReference() {
                        ConsolidationResult result = Consolidation.consolidate(ELIGIBLE,
                                        EnrichmentOutcome.notFound(List.of("vehicleRef")), null, List.of());

                        assertThat(result.outcome()).isEqualTo(Outcome.NEEDS_INFORMATION);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.ENRICHMENT_NOT_FOUND);
                        assertThat(result.missingFields())
                                        .containsExactly(new MissingField("risk.vehicleRef", "ENRICHMENT_NOT_FOUND"));
                }

                @Test
                void t25_everyDeclaredReferenceBecomesAMissingField() {
                        ConsolidationResult result = Consolidation.consolidate(ELIGIBLE,
                                        EnrichmentOutcome.notFound(List.of("vehicleRef", "principalDriverRef")), null,
                                        List.of());

                        assertThat(result.missingFields()).containsExactly(
                                        new MissingField("risk.vehicleRef", "ENRICHMENT_NOT_FOUND"),
                                        new MissingField("risk.principalDriverRef", "ENRICHMENT_NOT_FOUND"));
                        assertThat(result.missingFields()).extracting(field -> MissingFieldLabels.labelOf(field.path()))
                                        .containsExactly("Veículo", "Condutor principal");
                }

                @Test
                void enrichmentDecidesBeforeAnySelectionOrInsurerResultRecordedLater() {
                        ConsolidationResult notFound = Consolidation.consolidate(ELIGIBLE,
                                        EnrichmentOutcome.notFound(List.of("vehicleRef")), BOTH,
                                        List.of(result(Insurers.ALPHA, InsurerStatus.QUOTED)));
                        ConsolidationResult failed = Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.failed(),
                                        BOTH,
                                        List.of(result(Insurers.ALPHA, InsurerStatus.QUOTED)));

                        assertThat(notFound.outcome()).isEqualTo(Outcome.NEEDS_INFORMATION);
                        assertThat(notFound.outcomeReason()).isEqualTo(OutcomeReason.ENRICHMENT_NOT_FOUND);
                        assertThat(failed.outcome()).isEqualTo(Outcome.TECHNICAL_FAILURE);
                        assertThat(failed.outcomeReason()).isEqualTo(OutcomeReason.ENRICHMENT_FAILED);
                }

                @Test
                void t27_enrichmentFailureIsTechnicalFailureWithoutInventingData() {
                        ConsolidationResult result = Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.failed(),
                                        null, List.of());

                        assertThat(result.outcome()).isEqualTo(Outcome.TECHNICAL_FAILURE);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.ENRICHMENT_FAILED);
                        assertThat(result.missingFields()).isEmpty();
                }

                @Test
                void t26_noApplicableInsurerIsNoOffer() {
                        ConsolidationResult result = Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.found(),
                                        List.of(),
                                        List.of());

                        assertThat(result.outcome()).isEqualTo(Outcome.NO_OFFER);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.NO_APPLICABLE_INSURER);
                }

                @Test
                void stepsThatNeverRanAreAWorkflowFailure() {
                        assertThat(Consolidation.consolidate(null, null, null, List.of()).outcomeReason())
                                        .isEqualTo(OutcomeReason.WORKFLOW_FAILED);
                        assertThat(Consolidation.consolidate(ELIGIBLE, null, null, List.of()).outcomeReason())
                                        .isEqualTo(OutcomeReason.WORKFLOW_FAILED);
                        assertThat(Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.found(), null, List.of())
                                        .outcome())
                                        .isEqualTo(Outcome.TECHNICAL_FAILURE);
                }
        }

        @Nested
        class WithInsurerResults {

                @Test
                void t01_offersFromEveryInsurerAreCompleted() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.QUOTED),
                                        result(Insurers.BETA, InsurerStatus.QUOTED));

                        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
                        assertThat(result.outcomeReason()).isNull();
                }

                @Test
                void t01_singleInsurerOfferIsCompleted() {
                        ConsolidationResult result = Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.found(),
                                        List.of(Insurers.ALPHA), List.of(result(Insurers.ALPHA, InsurerStatus.QUOTED)));

                        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
                }

                @Test
                void t07_offerAndDeclineAreCompleted() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.QUOTED),
                                        result(Insurers.BETA, InsurerStatus.DECLINED));

                        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
                        assertThat(result.outcomeReason()).isNull();
                        assertThat(result.missingFields()).isEmpty();
                }

                @Test
                void t08_offerAndTimeoutArePartial() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.QUOTED),
                                        result(Insurers.BETA, InsurerStatus.TIMED_OUT));

                        assertThat(result.outcome()).isEqualTo(Outcome.PARTIAL);
                        assertThat(result.outcomeReason()).isNull();
                }

                @Test
                void offerAndPendencyArePartial() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.QUOTED),
                                        result(Insurers.BETA, InsurerStatus.NEEDS_INFORMATION,
                                                        new MissingField("risk.principalDriver.licenseDate",
                                                                        "REQUIRED")));

                        assertThat(result.outcome()).isEqualTo(Outcome.PARTIAL);
                        assertThat(result.missingFields()).isEmpty();
                }

                @Test
                void offerAndUnavailableArePartial() {
                        assertThat(insurers(result(Insurers.ALPHA, InsurerStatus.UNAVAILABLE),
                                        result(Insurers.BETA, InsurerStatus.QUOTED)).outcome())
                                        .isEqualTo(Outcome.PARTIAL);
                }

                @Test
                void pendencyWithoutOfferIsNeedsInformationAndPreservesTheMissingFields() {
                        MissingField license = new MissingField("risk.principalDriver.licenseDate", "REQUIRED");

                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.UNAVAILABLE),
                                        result(Insurers.BETA, InsurerStatus.NEEDS_INFORMATION, license));

                        assertThat(result.outcome()).isEqualTo(Outcome.NEEDS_INFORMATION);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.INSURER_NEEDS_INFORMATION);
                        assertThat(result.missingFields()).containsExactly(license);
                }

                @Test
                void repeatedMissingPathsAppearOnce() {
                        MissingField license = new MissingField("risk.principalDriver.licenseDate", "REQUIRED");

                        ConsolidationResult result = insurers(
                                        result(Insurers.ALPHA, InsurerStatus.NEEDS_INFORMATION, license),
                                        result(Insurers.BETA, InsurerStatus.NEEDS_INFORMATION, license));

                        assertThat(result.missingFields()).containsExactly(license);
                }

                @Test
                void t10_technicalFailureWithoutOfferNorPendencyIsTechnicalFailure() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.UNAVAILABLE),
                                        result(Insurers.BETA, InsurerStatus.TIMED_OUT));

                        assertThat(result.outcome()).isEqualTo(Outcome.TECHNICAL_FAILURE);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.INSURER_TECHNICAL_FAILURE);
                }

                @Test
                void declineAndTechnicalFailureIsTechnicalFailureNotNoOffer() {
                        assertThat(insurers(result(Insurers.ALPHA, InsurerStatus.DECLINED),
                                        result(Insurers.BETA, InsurerStatus.UNAVAILABLE)).outcome())
                                        .isEqualTo(Outcome.TECHNICAL_FAILURE);
                }

                @Test
                void t09_everyInsurerDecliningIsNoOffer() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.DECLINED),
                                        result(Insurers.BETA, InsurerStatus.DECLINED));

                        assertThat(result.outcome()).isEqualTo(Outcome.NO_OFFER);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.ALL_INSURERS_DECLINED);
                }

                @Test
                void selectedInsurerWithoutResultCountsAsTechnicalFailure() {
                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.QUOTED));

                        assertThat(result.outcome()).isEqualTo(Outcome.PARTIAL);
                }

                @Test
                void nonTerminalResultCountsAsTechnicalFailure() {
                        assertThat(insurers(result(Insurers.ALPHA, InsurerStatus.DECLINED),
                                        result(Insurers.BETA, InsurerStatus.PROCESSING)).outcome())
                                        .isEqualTo(Outcome.TECHNICAL_FAILURE);
                }

                @Test
                void declineAndPendencyIsNeedsInformationNotNoOffer() {
                        MissingField license = new MissingField("risk.principalDriver.licenseDate",
                                        "MISSING_LICENSE_DATE");

                        ConsolidationResult result = insurers(result(Insurers.ALPHA, InsurerStatus.DECLINED),
                                        result(Insurers.BETA, InsurerStatus.NEEDS_INFORMATION, license));

                        assertThat(result.outcome()).isEqualTo(Outcome.NEEDS_INFORMATION);
                        assertThat(result.outcomeReason()).isEqualTo(OutcomeReason.INSURER_NEEDS_INFORMATION);
                        assertThat(result.missingFields()).containsExactly(license);
                }

                /**
                 * As linhas do PRD §8 para duas seguradoras, nas duas ordens: a precedência não
                 * depende de quem respondeu.
                 */
                @ParameterizedTest(name = "{0} + {1} = {2}")
                @CsvSource({
                                "QUOTED,            QUOTED,            COMPLETED,",
                                "QUOTED,            DECLINED,          COMPLETED,",
                                "QUOTED,            NEEDS_INFORMATION, PARTIAL,",
                                "QUOTED,            UNAVAILABLE,       PARTIAL,",
                                "QUOTED,            TIMED_OUT,         PARTIAL,",
                                "NEEDS_INFORMATION, NEEDS_INFORMATION, NEEDS_INFORMATION, INSURER_NEEDS_INFORMATION",
                                "NEEDS_INFORMATION, DECLINED,          NEEDS_INFORMATION, INSURER_NEEDS_INFORMATION",
                                "NEEDS_INFORMATION, UNAVAILABLE,       NEEDS_INFORMATION, INSURER_NEEDS_INFORMATION",
                                "NEEDS_INFORMATION, TIMED_OUT,         NEEDS_INFORMATION, INSURER_NEEDS_INFORMATION",
                                "UNAVAILABLE,       UNAVAILABLE,       TECHNICAL_FAILURE, INSURER_TECHNICAL_FAILURE",
                                "UNAVAILABLE,       TIMED_OUT,         TECHNICAL_FAILURE, INSURER_TECHNICAL_FAILURE",
                                "TIMED_OUT,         TIMED_OUT,         TECHNICAL_FAILURE, INSURER_TECHNICAL_FAILURE",
                                "DECLINED,          UNAVAILABLE,       TECHNICAL_FAILURE, INSURER_TECHNICAL_FAILURE",
                                "DECLINED,          TIMED_OUT,         TECHNICAL_FAILURE, INSURER_TECHNICAL_FAILURE",
                                "DECLINED,          DECLINED,          NO_OFFER,          ALL_INSURERS_DECLINED"
                })
                void everyPairOfTerminalResultsFollowsThePrecedence(InsurerStatus first, InsurerStatus second,
                                Outcome outcome,
                                OutcomeReason reason) {
                        ConsolidationResult alphaFirst = insurers(result(Insurers.ALPHA, first),
                                        result(Insurers.BETA, second));
                        ConsolidationResult betaFirst = insurers(result(Insurers.ALPHA, second),
                                        result(Insurers.BETA, first));

                        for (ConsolidationResult result : List.of(alphaFirst, betaFirst)) {
                                assertThat(result.outcome()).isEqualTo(outcome);
                                assertThat(result.outcomeReason()).isEqualTo(reason);
                        }
                }

                @Test
                void resultsOfInsurersOutsideTheSelectionAreIgnored() {
                        ConsolidationResult result = Consolidation.consolidate(ELIGIBLE, EnrichmentOutcome.found(),
                                        List.of(Insurers.ALPHA), List.of(result(Insurers.ALPHA, InsurerStatus.DECLINED),
                                                        result(Insurers.BETA, InsurerStatus.QUOTED)));

                        assertThat(result.outcome()).isEqualTo(Outcome.NO_OFFER);
                }
        }
}

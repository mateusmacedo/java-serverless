package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Regras {@code elig-1} e {@code sel-1} no nível de regra, sem nenhuma chamada externa. */
class RulesTest {

    static QuoteSnapshot snapshot(Usage usage, int year, boolean salvage, String licenseDate) {
        return new QuoteSnapshot("AUTO", "customer-ana", new QuoteSnapshot.Party("customer-ana", "Ana"),
                new QuoteSnapshot.Risk("vehicle-x", usage, "driver-x",
                        new QuoteSnapshot.Vehicle("vehicle-x", "Hatch", "Fiat", "Argo", year, salvage),
                        new QuoteSnapshot.Driver("driver-x", "Ana", licenseDate)),
                "COMPREHENSIVE", new QuoteSnapshot.CoverageProfile("COMPREHENSIVE", "Completa", "Tudo", List.of()),
                "2026-12-01");
    }

    @Test
    void t23_salvageVehicleIsIneligibleBeforeAnyExternalQuery() {
        EligibilityResult result = EligibilityRule.evaluate(snapshot(Usage.PERSONAL, 2020, true, "2010-01-01"));

        assertThat(result.decision()).isEqualTo(EligibilityDecision.INELIGIBLE);
        assertThat(result.reasonCode()).isEqualTo(OutcomeReason.SALVAGE_VEHICLE);
        assertThat(result.ruleVersion()).isEqualTo("elig-1");
    }

    @Test
    void t24_driverWithoutLicenseDateNeedsInformationOnTheDriverReference() {
        EligibilityResult result = EligibilityRule.evaluate(snapshot(Usage.PERSONAL, 2020, false, null));

        assertThat(result.decision()).isEqualTo(EligibilityDecision.NEEDS_INFORMATION);
        assertThat(result.reasonCode()).isEqualTo(OutcomeReason.MISSING_LICENSE_DATE);
        assertThat(result.missingFields())
                .containsExactly(new MissingField("risk.principalDriverRef", "MISSING_LICENSE_DATE"));
        assertThat(MissingFieldLabels.labelOf("risk.principalDriverRef")).isEqualTo("Condutor principal");
    }

    @Test
    void incompleteInputTakesPrecedenceOverRefusal() {
        EligibilityResult result = EligibilityRule.evaluate(snapshot(Usage.PERSONAL, 2020, true, " "));

        assertThat(result.decision()).isEqualTo(EligibilityDecision.NEEDS_INFORMATION);
    }

    @Test
    void completeAndSoundRiskIsEligible() {
        EligibilityResult result = EligibilityRule.evaluate(snapshot(Usage.PERSONAL, 2020, false, "2010-01-01"));

        assertThat(result.decision()).isEqualTo(EligibilityDecision.ELIGIBLE);
        assertThat(result.reasonCode()).isNull();
        assertThat(result.missingFields()).isEmpty();
    }

    @Test
    void sameInputAndRuleVersionGiveTheSameDecision() {
        QuoteSnapshot snapshot = snapshot(Usage.COMMERCIAL, 2015, true, "2010-01-01");

        assertThat(EligibilityRule.evaluate(snapshot)).isEqualTo(EligibilityRule.evaluate(snapshot));
    }

    @Test
    void vehicleBefore2000HasNoApplicableInsurer() {
        assertThat(InsurerSelectionRule.select(Usage.PERSONAL, 1999, 2).insurerIds()).isEmpty();
        assertThat(InsurerSelectionRule.select(Usage.PERSONAL, 2000, 2).insurerIds()).isNotEmpty();
    }

    @Test
    void commercialUseQuotesOnlyOnAlpha() {
        assertThat(InsurerSelectionRule.select(Usage.COMMERCIAL, 2020, 2).insurerIds()).containsExactly("alpha");
    }

    @Test
    void personalUseQuotesOnAlphaAndBetaLimitedToMaxInsurers() {
        assertThat(InsurerSelectionRule.select(Usage.PERSONAL, 2020, 2).insurerIds())
                .containsExactly("alpha", "beta");
        assertThat(InsurerSelectionRule.select(Usage.PERSONAL, 2020, 1).insurerIds()).containsExactly("alpha");
        assertThat(InsurerSelectionRule.select(Usage.PERSONAL, 2020, 2).ruleVersion()).isEqualTo("sel-1");
        assertThatThrownBy(() -> InsurerSelectionRule.select(Usage.PERSONAL, 2020, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everySelectableInsurerHasAName() {
        assertThat(Insurers.all()).allSatisfy(id -> assertThat(Insurers.nameOf(id)).isPresent());
        assertThat(Insurers.nameOf("gamma")).isEmpty();
    }
}

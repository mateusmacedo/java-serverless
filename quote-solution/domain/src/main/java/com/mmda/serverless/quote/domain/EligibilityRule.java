package com.mmda.serverless.quote.domain;

import java.util.List;

/**
 * Regra de elegibilidade {@code elig-1}, só com dados de entrada, aplicada antes de qualquer chamada
 * externa paga (PRD §9, passo 2).
 * <p>
 * WHY: a entrada incompleta vem antes da recusa porque a precedência da consolidação (PRD §8) coloca
 * {@code NEEDS_INFORMATION} acima de {@code INELIGIBLE}.
 */
public final class EligibilityRule {

    public static final String VERSION = "elig-1";

    private EligibilityRule() {
    }

    public static EligibilityResult evaluate(QuoteSnapshot snapshot) {
        QuoteSnapshot.Driver driver = snapshot.risk().principalDriver();
        if (driver == null || driver.licenseDate() == null || driver.licenseDate().isBlank()) {
            return new EligibilityResult(EligibilityDecision.NEEDS_INFORMATION, OutcomeReason.MISSING_LICENSE_DATE,
                    List.of(new MissingField("risk.principalDriverRef", OutcomeReason.MISSING_LICENSE_DATE.name())),
                    VERSION);
        }
        QuoteSnapshot.Vehicle vehicle = snapshot.risk().vehicle();
        if (vehicle != null && vehicle.salvage()) {
            return new EligibilityResult(EligibilityDecision.INELIGIBLE, OutcomeReason.SALVAGE_VEHICLE, List.of(),
                    VERSION);
        }
        return new EligibilityResult(EligibilityDecision.ELIGIBLE, null, List.of(), VERSION);
    }
}

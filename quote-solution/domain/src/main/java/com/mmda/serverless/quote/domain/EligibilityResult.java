package com.mmda.serverless.quote.domain;

import java.util.List;
import java.util.Objects;

/**
 * Decisão de elegibilidade de uma versão, com a versão da regra que a produziu.
 */
public record EligibilityResult(
        EligibilityDecision decision,
        OutcomeReason reasonCode,
        List<MissingField> missingFields,
        String ruleVersion) {

    public EligibilityResult {
        Objects.requireNonNull(decision, "decision");
        missingFields = List.copyOf(missingFields);
    }
}

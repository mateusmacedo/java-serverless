package com.mmda.serverless.quote.domain;

/** Decisão da regra de elegibilidade; só {@link #ELIGIBLE} segue para o enriquecimento. */
public enum EligibilityDecision {
    ELIGIBLE,
    INELIGIBLE,
    NEEDS_INFORMATION
}

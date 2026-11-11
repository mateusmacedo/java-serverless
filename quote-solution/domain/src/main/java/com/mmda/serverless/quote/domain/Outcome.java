package com.mmda.serverless.quote.domain;

/** Resultado agregado de uma versão; nulo enquanto a fase não é {@link Phase#TERMINAL}. */
public enum Outcome {
    COMPLETED,
    PARTIAL,
    NEEDS_INFORMATION,
    INELIGIBLE,
    NO_OFFER,
    TECHNICAL_FAILURE
}

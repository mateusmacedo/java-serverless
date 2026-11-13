package com.mmda.serverless.quote.domain;

/**
 * Motivo do {@link Outcome} agregado, enum fechado do contrato. Nulo em {@link Outcome#COMPLETED} e
 * {@link Outcome#PARTIAL}.
 */
public enum OutcomeReason {
    SALVAGE_VEHICLE,
    MISSING_LICENSE_DATE,
    ENRICHMENT_NOT_FOUND,
    ENRICHMENT_FAILED,
    NO_APPLICABLE_INSURER,
    ALL_INSURERS_DECLINED,
    INSURER_NEEDS_INFORMATION,
    INSURER_TECHNICAL_FAILURE,
    WORKFLOW_FAILED
}

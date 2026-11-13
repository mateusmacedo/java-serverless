package com.mmda.serverless.provider.domain;

/**
 * Estado técnico da consulta externa, separado do resultado (PRD, §10). {@code UNKNOWN} é resultado
 * ambíguo, que a reconciliação resolve sem repetir a chamada.
 */
public enum QueryState {
    IN_FLIGHT,
    COMPLETED,
    FAILED,
    UNKNOWN;

    public boolean isSettled() {
        return this != IN_FLIGHT;
    }
}

package com.mmda.serverless.provider.domain.operations;

/**
 * Situação de uma reconciliação manual ({@code OPERATION#}). {@code RUNNING} só existe na tabela da provider,
 * enquanto o {@code reconcileWorker} trabalha; o {@code QueryReconciled} leva só o resultado.
 */
public enum ReconcileStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}

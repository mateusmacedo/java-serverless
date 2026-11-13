package com.mmda.serverless.provider.functions;

/**
 * Evidência imutável da consulta (desenho, §5).
 *
 * @param resultType {@code FOUND}, {@code NOT_FOUND} ou {@code FAILED} no enriquecimento; o status na seguradora
 * @param checksum   SHA-256 da resposta bruta, ou {@code null} se não houve resposta
 */
record EvidenceDraft(
        String evidenceId,
        String resultType,
        String normalizedResult,
        String rawResponseRef,
        String observedAt,
        String receivedAt,
        String reusableUntil,
        String checksum,
        String errorClass) {
}

package com.mmda.serverless.provider.functions;

/**
 * Evidência imutável da consulta (desenho, §5). {@code resultType} é o do enriquecimento ou o status da
 * seguradora; {@code checksum} é o SHA-256 da resposta bruta, quando houve. {@code fencingVersion} é a
 * liderança sob a qual o enriquecimento foi obtido e {@code policyVersion}, a política de reuso em vigor.
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
        String errorClass,
        Long fencingVersion,
        String policyVersion) {

    /** Evidência sem liderança nem política de reuso, como a da seguradora. */
    EvidenceDraft(String evidenceId, String resultType, String normalizedResult, String rawResponseRef,
                  String observedAt, String receivedAt, String reusableUntil, String checksum, String errorClass) {
        this(evidenceId, resultType, normalizedResult, rawResponseRef, observedAt, receivedAt, reusableUntil, checksum,
                errorClass, null, null);
    }
}

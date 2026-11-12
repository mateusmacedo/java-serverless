package com.mmda.serverless.provider.functions;

/**
 * Evidência gravada, lida para responder a um {@code HIT} ou {@code JOIN} com o resultado de outra consulta.
 *
 * @param policyVersion política de reuso que produziu a evidência; {@code null} nas evidências do M1
 */
record StoredEvidence(
        String queryId,
        String evidenceId,
        String resultType,
        String normalizedResult,
        String reusableUntil,
        String errorClass,
        String policyVersion) {
}

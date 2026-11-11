package com.mmda.serverless.quote.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Precedência da consolidação do PRD §8. Lê o que a versão já gravou e decide {@code outcome},
 * {@code outcomeReason} e {@code missingFields}; não depende do estado da execução, então serve também à
 * reconciliação de versões cujo workflow terminou sem consolidar (M4).
 */
public final class Consolidation {

    private Consolidation() {
    }

    /**
     * Argumentos nulos indicam etapa que não rodou; seguradora selecionada sem resultado conta como falha
     * técnica.
     */
    public static ConsolidationResult consolidate(
            EligibilityResult eligibility,
            EnrichmentOutcome enrichment,
            List<String> selectedInsurers,
            List<InsurerResultView> insurerResults) {
        if (eligibility == null) {
            return ConsolidationResult.of(Outcome.TECHNICAL_FAILURE, OutcomeReason.WORKFLOW_FAILED);
        }
        switch (eligibility.decision()) {
            case NEEDS_INFORMATION:
                return new ConsolidationResult(Outcome.NEEDS_INFORMATION, eligibility.reasonCode(),
                        eligibility.missingFields());
            case INELIGIBLE:
                return ConsolidationResult.of(Outcome.INELIGIBLE, eligibility.reasonCode());
            case ELIGIBLE:
            default:
                break;
        }
        if (enrichment == null) {
            return ConsolidationResult.of(Outcome.TECHNICAL_FAILURE, OutcomeReason.WORKFLOW_FAILED);
        }
        switch (enrichment.resultType()) {
            case NOT_FOUND:
                return new ConsolidationResult(Outcome.NEEDS_INFORMATION, OutcomeReason.ENRICHMENT_NOT_FOUND,
                        enrichment.missingFields());
            case FAILED:
                return ConsolidationResult.of(Outcome.TECHNICAL_FAILURE, OutcomeReason.ENRICHMENT_FAILED);
            case FOUND:
            default:
                break;
        }
        if (selectedInsurers == null) {
            return ConsolidationResult.of(Outcome.TECHNICAL_FAILURE, OutcomeReason.WORKFLOW_FAILED);
        }
        if (selectedInsurers.isEmpty()) {
            return ConsolidationResult.of(Outcome.NO_OFFER, OutcomeReason.NO_APPLICABLE_INSURER);
        }
        return consolidateInsurers(selectedInsurers, insurerResults);
    }

    private static ConsolidationResult consolidateInsurers(List<String> selected, List<InsurerResultView> results) {
        Map<String, InsurerResultView> byInsurer = new LinkedHashMap<>();
        for (InsurerResultView result : results) {
            byInsurer.put(result.insurerId(), result);
        }
        int quoted = 0;
        boolean needsInformation = false;
        boolean technicalFailure = false;
        List<MissingField> missingFields = new ArrayList<>();
        for (String insurerId : selected) {
            InsurerResultView result = byInsurer.get(insurerId);
            InsurerStatus status = result == null ? InsurerStatus.PENDING : result.status();
            switch (status) {
                case QUOTED -> quoted++;
                case NEEDS_INFORMATION -> {
                    needsInformation = true;
                    result.missingFields().stream()
                            .filter(field -> missingFields.stream().noneMatch(known -> known.path().equals(field.path())))
                            .forEach(missingFields::add);
                }
                case DECLINED -> {
                }
                default -> technicalFailure = true;
            }
        }
        if (quoted > 0) {
            Outcome outcome = needsInformation || technicalFailure ? Outcome.PARTIAL : Outcome.COMPLETED;
            return ConsolidationResult.of(outcome, null);
        }
        if (needsInformation) {
            return new ConsolidationResult(Outcome.NEEDS_INFORMATION, OutcomeReason.INSURER_NEEDS_INFORMATION,
                    missingFields);
        }
        if (technicalFailure) {
            return ConsolidationResult.of(Outcome.TECHNICAL_FAILURE, OutcomeReason.INSURER_TECHNICAL_FAILURE);
        }
        return ConsolidationResult.of(Outcome.NO_OFFER, OutcomeReason.ALL_INSURERS_DECLINED);
    }

    /** Resultado de uma seguradora como a consolidação o enxerga. */
    public record InsurerResultView(String insurerId, InsurerStatus status, List<MissingField> missingFields) {

        public InsurerResultView {
            Objects.requireNonNull(insurerId, "insurerId");
            Objects.requireNonNull(status, "status");
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }

    public record ConsolidationResult(Outcome outcome, OutcomeReason outcomeReason, List<MissingField> missingFields) {

        public ConsolidationResult {
            Objects.requireNonNull(outcome, "outcome");
            missingFields = List.copyOf(missingFields);
        }

        static ConsolidationResult of(Outcome outcome, OutcomeReason reason) {
            return new ConsolidationResult(outcome, reason, List.of());
        }
    }
}

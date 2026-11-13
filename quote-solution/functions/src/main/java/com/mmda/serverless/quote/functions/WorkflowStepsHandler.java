package com.mmda.serverless.quote.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * Lambda única dos passos simples da máquina, escolhidos pelo campo
 * {@code step}. WHY: no Floci cada
 * função Java vira um container, e a memória da máquina de desenvolvimento é
 * limitada (ADR 0014).
 *
 * @Singleton is intentional here because this Lambda handler is created once
 *            per execution environment, and the project relies on a single
 *            shared bean instance execution environment, and the project relies
 *            on a single shared bean instancewithout a CDI request scope in the
 *            serverless runtime.
 */
@Singleton
@Named("workflow-steps")
public class WorkflowStepsHandler implements RequestHandler<StepRequest, Map<String, Object>> {

    private final WorkflowSteps steps;

    WorkflowStepsHandler(WorkflowSteps steps) {
        this.steps = steps;
    }

    @Override
    public Map<String, Object> handleRequest(StepRequest request, Context context) {
        return switch (request.step()) {
            case "load-snapshot" -> steps.loadSnapshot(request.quoteId(), request.version());
            case "evaluate-eligibility" -> steps.evaluateEligibility(request.quoteId(), request.version());
            case "select-insurers" -> steps.selectInsurers(request.quoteId(), request.version());
            case "record-enrichment-outcome" ->
                steps.recordEnrichmentOutcome(request.quoteId(), request.version(), request.errorName());
            case "record-insurer-outcome" -> steps.recordInsurerOutcome(request.quoteId(), request.version(),
                    request.insurerId(), request.outcome(), request.errorName());
            case "consolidate" -> steps.consolidate(request.quoteId(), request.version());
            case null, default -> throw new IllegalArgumentException("unknown workflow step: " + request.step());
        };
    }
}

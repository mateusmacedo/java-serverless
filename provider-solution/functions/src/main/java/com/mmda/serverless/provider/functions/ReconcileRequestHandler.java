package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.operations.QueryReconcileRequest;
import com.mmda.serverless.provider.domain.operations.ReconcileStatus;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Um {@code QueryReconcileRequested} (desenho, §8): grava o {@code OPERATION#}, confere que a consulta é da mesma
 * organização, reconcilia pelo mesmo caminho do {@code reconciler} e publica {@code QueryReconciled} pelo outbox.
 * Consulta de outra organização responde como inexistente, para não revelar que ela existe.
 */
@Component
class ReconcileRequestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconcileRequestHandler.class);

    private final OperationLedger operations;
    private final QueryLedger ledger;
    private final QueryReconciliation reconciliation;
    private final EventJson json;
    private final Clock clock;

    ReconcileRequestHandler(OperationLedger operations, QueryLedger ledger, QueryReconciliation reconciliation,
                            EventJson json, Clock clock) {
        this.operations = operations;
        this.ledger = ledger;
        this.reconciliation = reconciliation;
        this.json = json;
        this.clock = clock;
    }

    void handle(String messageBody) {
        QueryReconcileRequest request = json.detail(messageBody, QueryReconcileRequest.class);
        if (operations.start(request, clock.instant()).isEmpty()) {
            LOGGER.info("Reconciliation already has its result: operationId={}", request.operationId());
            return;
        }
        Optional<QueryRecord> query = ledger.load(request.queryId())
                .filter(found -> request.organizationId().equals(found.organizationId()));
        QueryReconciliation.Result result = query
                .map(reconciliation::reconcile)
                .orElseGet(() -> new QueryReconciliation.Result(QueryReconciliation.Outcome.NOT_FOUND,
                        "Consulta não encontrada para a organização"));
        ReconcileStatus status = switch (result.outcome()) {
            case SETTLED, CONCLUDED -> ReconcileStatus.SUCCEEDED;
            case NOT_FOUND, IN_PROGRESS, UNKNOWN -> ReconcileStatus.FAILED;
        };
        boolean recorded = operations.finish(request, status, result.safeSummary(), clock.instant());
        LOGGER.info("Manual reconciliation: operationId={} queryId={} outcome={} status={} recorded={}",
                request.operationId(), request.queryId(), result.outcome(), status, recorded);
    }
}

package com.mmda.serverless.provider.functions;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Lambda da fila {@code reconcile-requests}: a reconciliação manual pedida pela quote (M4). */
@Component("reconcileWorker")
class ReconcileWorkerFunction implements Function<SQSEvent, SQSBatchResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconcileWorkerFunction.class);

    private final ReconcileRequestHandler handler;

    ReconcileWorkerFunction(ReconcileRequestHandler handler) {
        this.handler = handler;
    }

    @Override
    public SQSBatchResponse apply(SQSEvent event) {
        return SqsBatches.process(event, handler::handle, LOGGER);
    }
}

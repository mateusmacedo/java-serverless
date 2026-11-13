package com.mmda.serverless.provider.functions;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Lambda da fila {@code insurer-requests}. */
@Component("insurerWorker")
class InsurerWorkerFunction implements Function<SQSEvent, SQSBatchResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsurerWorkerFunction.class);

    private final InsurerRequestHandler handler;

    InsurerWorkerFunction(InsurerRequestHandler handler) {
        this.handler = handler;
    }

    @Override
    public SQSBatchResponse apply(SQSEvent event) {
        return SqsBatches.processMessages(event, handler::handle, LOGGER);
    }
}

package com.mmda.serverless.provider.functions;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Falha parcial no ESM de SQS ({@code ReportBatchItemFailures}): só a mensagem
 * que falhou volta à fila e,
 * depois de {@code maxReceiveCount}, vai para a DLQ.
 */
final class SqsBatches {

    private SqsBatches() {
    }

    static SQSBatchResponse process(SQSEvent event, Consumer<String> handler, Logger logger) {
        return processMessages(event, message -> handler.accept(message.body()), logger);
    }

    static SQSBatchResponse processMessages(SQSEvent event, Consumer<QueuedMessage> handler, Logger logger) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        List<SQSEvent.SQSMessage> records = event.getRecords() == null ? List.of() : event.getRecords();
        for (SQSEvent.SQSMessage message : records) {
            try {
                handler.accept(QueuedMessage.of(message));
            } catch (RuntimeException exception) {
                logger.error("Message {} failed and returns to the queue", message.getMessageId(), exception);
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }
}

package com.mmda.serverless.quote.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mmda.serverless.quote.functions.LambdaEvents.BatchResponse;
import com.mmda.serverless.quote.functions.LambdaEvents.SqsEvent;
import com.mmda.serverless.quote.functions.LambdaEvents.SqsMessage;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Consome a fila {@code issues} ({@code QueryReconciled},
 * {@code OperationIssueRaised} e
 * {@code OperationIssueUpdated}). Uma falha devolve só a mensagem em
 * {@code batchItemFailures}; depois do
 * {@code maxReceiveCount}, ela vai para a {@code issues-dlq}, que o
 * {@code reconciler} reprocessa.
 */
@Singleton
@Named("issue-projector")
public class IssueProjectorHandler implements RequestHandler<SqsEvent, BatchResponse> {

    private static final Logger LOG = Logger.getLogger(IssueProjectorHandler.class);

    private final IssueProjector projector;

    IssueProjectorHandler(IssueProjector projector) {
        this.projector = projector;
    }

    @Override
    public BatchResponse handleRequest(SqsEvent event, Context context) {
        List<String> failures = new ArrayList<>();
        for (SqsMessage message : event.records()) {
            try {
                LOG.infof("message %s: %s", message.messageId(), projector.handle(message.body()));
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "message %s failed and will be redelivered", message.messageId());
                failures.add(message.messageId());
            }
        }
        return BatchResponse.of(failures);
    }
}

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
 * Consome a fila {@code results} ({@code EnrichmentResolved} e {@code InsurerResultRecorded}). Uma falha
 * devolve só a mensagem em {@code batchItemFailures}; depois do {@code maxReceiveCount}, ela vai para a DLQ.
 */
@Singleton
@Named("resume")
public class ResumeHandler implements RequestHandler<SqsEvent, BatchResponse> {

    private static final Logger LOG = Logger.getLogger(ResumeHandler.class);

    private final ResultResumer resumer;

    ResumeHandler(ResultResumer resumer) {
        this.resumer = resumer;
    }

    @Override
    public BatchResponse handleRequest(SqsEvent event, Context context) {
        List<String> failures = new ArrayList<>();
        for (SqsMessage message : event.records()) {
            try {
                ResultResumer.Disposition disposition = resumer.handle(message.body());
                LOG.infof("message %s: %s", message.messageId(), disposition);
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "message %s failed and will be redelivered", message.messageId());
                failures.add(message.messageId());
            }
        }
        return BatchResponse.of(failures);
    }
}

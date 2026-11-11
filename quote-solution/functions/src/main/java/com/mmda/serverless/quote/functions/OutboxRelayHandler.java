package com.mmda.serverless.quote.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.functions.LambdaEvents.BatchResponse;
import com.mmda.serverless.quote.functions.LambdaEvents.StreamEvent;
import com.mmda.serverless.quote.functions.LambdaEvents.StreamRecord;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Publica os {@code INSERT} de outbox recebidos do stream da tabela, registro a registro (§7 do desenho).
 * Uma falha transitória devolve o número de sequência em {@code batchItemFailures} e encerra o lote:
 * com {@code ReportBatchItemFailures}, o stream reentrega a partir dele, inclusive os seguintes. Uma
 * falha permanente fica gravada no item, que segue {@code OUTBOX_PENDING} para o {@code reconciler}.
 */
@Singleton
@Named("outbox-relay")
public class OutboxRelayHandler implements RequestHandler<StreamEvent, BatchResponse> {

    private static final Logger LOG = Logger.getLogger(OutboxRelayHandler.class);

    private final OutboxPublisher publisher;

    OutboxRelayHandler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public BatchResponse handleRequest(StreamEvent event, Context context) {
        for (StreamRecord record : event.records()) {
            if (!"INSERT".equals(record.eventName()) || record.dynamodb() == null
                    || record.dynamodb().newImage() == null) {
                continue;
            }
            Map<String, AttributeValue> image = LambdaEvents.toItem(record.dynamodb().newImage());
            if (!QuoteKeys.TYPE_OUTBOX.equals(Attr.string(image, QuoteKeys.TYPE))) {
                continue;
            }
            OutboxPublisher.Result result = publisher.publish(OutboxPublisher.OutboxItem.from(image));
            if (result == OutboxPublisher.Result.TRANSIENT_FAILURE) {
                LOG.warnf("relay stops at sequence %s for redelivery", record.dynamodb().sequenceNumber());
                return BatchResponse.of(List.of(record.dynamodb().sequenceNumber()));
            }
        }
        return BatchResponse.of(List.of());
    }
}

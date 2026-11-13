package com.mmda.serverless.provider.functions;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Relay do outbox (desenho, §7): recebe só os {@code INSERT} de itens
 * {@code OUTBOX} pelo filtro do ESM e
 * trata cada registro separadamente. No primeiro erro transitório devolve o
 * número de sequência dele em
 * {@code batchItemFailures}: o stream reentrega a partir dali, inclusive os
 * seguintes.
 */
@Component("outboxRelay")
class OutboxRelayFunction implements Function<DynamodbEvent, StreamsEventResponse> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayFunction.class);

    private final OutboxPublisher publisher;
    private final OutboxStore outbox;
    private final Clock clock;

    OutboxRelayFunction(OutboxPublisher publisher, OutboxStore outbox, Clock clock) {
        this.publisher = publisher;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public StreamsEventResponse apply(DynamodbEvent event) {
        List<DynamodbEvent.DynamodbStreamRecord> records = event.getRecords() == null ? List.of() : event.getRecords();
        for (DynamodbEvent.DynamodbStreamRecord record : records) {
            StreamRecord stream = record.getDynamodb();
            if (stream == null || stream.getNewImage() == null) {
                continue;
            }
            if (relay(StreamImages.toSdk(stream.getNewImage())) == OutboxPublisher.Result.TRANSIENT_FAILURE) {
                return new StreamsEventResponse(List.of(
                        new StreamsEventResponse.BatchItemFailure(stream.getSequenceNumber())));
            }
        }
        return new StreamsEventResponse(List.of());
    }

    private OutboxPublisher.Result relay(Map<String, AttributeValue> image) {
        Optional<OutboxEntry> entry = OutboxEntry.from(image);
        if (entry.isPresent()) {
            return publisher.publish(entry.get());
        }
        Optional<String> eventId = OutboxEntry.eventIdOf(image);
        LOGGER.error("Outbox record without eventId, detailType or detail: {}", eventId.orElse("unknown"));
        if (eventId.isEmpty()) {
            return OutboxPublisher.Result.PERMANENT_FAILURE;
        }
        try {
            outbox.recordFailure(eventId.get(), "Incomplete outbox item", clock.instant());
            return OutboxPublisher.Result.PERMANENT_FAILURE;
        } catch (SdkException exception) {
            return OutboxPublisher.Result.TRANSIENT_FAILURE;
        }
    }
}

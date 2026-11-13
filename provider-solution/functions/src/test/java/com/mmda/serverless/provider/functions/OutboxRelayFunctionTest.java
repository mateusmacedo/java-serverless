package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRelayFunctionTest {

    @Mock
    private OutboxPublisher publisher;

    @Mock
    private OutboxStore outbox;

    @Test
    void firstTransientFailureIsReportedAndTheRestOfTheBatchWaitsForTheRedelivery() {
        when(publisher.publish(entry("e1"))).thenReturn(OutboxPublisher.Result.PUBLISHED);
        when(publisher.publish(entry("e2"))).thenReturn(OutboxPublisher.Result.TRANSIENT_FAILURE);
        OutboxRelayFunction relay = new OutboxRelayFunction(publisher, outbox, Clock.systemUTC());

        StreamsEventResponse response = relay
                .apply(event(record("e1", "100"), record("e2", "200"), record("e3", "300")));

        assertThat(response.getBatchItemFailures())
                .extracting(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("200");
        verify(publisher, never()).publish(entry("e3"));
    }

    @Test
    void permanentFailureDoesNotStopTheBatch() {
        when(publisher.publish(any())).thenReturn(OutboxPublisher.Result.PERMANENT_FAILURE,
                OutboxPublisher.Result.PUBLISHED);
        OutboxRelayFunction relay = new OutboxRelayFunction(publisher, outbox, Clock.systemUTC());

        StreamsEventResponse response = relay.apply(event(record("e1", "100"), record("e2", "200")));

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(publisher).publish(entry("e2"));
    }

    @Test
    void incompleteItemIsRecordedAsPermanentFailure() {
        DynamodbEvent.DynamodbStreamRecord incomplete = new DynamodbEvent.DynamodbStreamRecord();
        incomplete.setDynamodb(new StreamRecord().withSequenceNumber("100").withNewImage(Map.of(
                "pk", new AttributeValue().withS("OUTBOX#e1"),
                "type", new AttributeValue().withS("OUTBOX"))));
        OutboxRelayFunction relay = new OutboxRelayFunction(publisher, outbox, Clock.systemUTC());

        StreamsEventResponse response = relay.apply(event(incomplete));

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(outbox).recordFailure(org.mockito.ArgumentMatchers.eq("e1"), any(), any());
        verify(publisher, never()).publish(any());
    }

    private static OutboxEntry entry(String eventId) {
        return new OutboxEntry(eventId, "InsurerResultRecorded", "{\"eventId\":\"" + eventId + "\"}");
    }

    private static DynamodbEvent.DynamodbStreamRecord record(String eventId, String sequence) {
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName("INSERT");
        record.setDynamodb(new StreamRecord().withSequenceNumber(sequence).withNewImage(Map.of(
                "pk", new AttributeValue().withS("OUTBOX#" + eventId),
                "type", new AttributeValue().withS("OUTBOX"),
                "eventId", new AttributeValue().withS(eventId),
                "detailType", new AttributeValue().withS("InsurerResultRecorded"),
                "detail", new AttributeValue().withS("{\"eventId\":\"" + eventId + "\"}"),
                "attempts", new AttributeValue().withN("0"))));
        return record;
    }

    private static DynamodbEvent event(DynamodbEvent.DynamodbStreamRecord... records) {
        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(records));
        return event;
    }
}

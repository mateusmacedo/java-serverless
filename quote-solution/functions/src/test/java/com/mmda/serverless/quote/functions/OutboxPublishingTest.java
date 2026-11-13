package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.functions.LambdaEvents.StreamData;
import com.mmda.serverless.quote.functions.LambdaEvents.StreamEvent;
import com.mmda.serverless.quote.functions.LambdaEvents.StreamRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Relay registro a registro e classificação das falhas de publicação (§7 do
 * desenho).
 */
class OutboxPublishingTest {

    static final String STATE_MACHINE = "arn:aws:states:us-east-1:000000000000:stateMachine:quote-flow";

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbClient dynamoDb;
    private EventBridgeClient eventBridge;
    private SfnClient sfn;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        eventBridge = mock(EventBridgeClient.class);
        sfn = mock(SfnClient.class);
        when(dynamoDb.updateItem(any(UpdateItemRequest.class))).thenReturn(UpdateItemResponse.builder().build());
        QuoteFunctionsConfig config = new TestConfig();
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);
        publisher = new OutboxPublisher(new QuoteTable(dynamoDb, config, mapper), eventBridge, sfn, mapper, config,
                clock);
    }

    @Test
    void relayPublishesEveryOutboxInsertInOrder() {
        when(eventBridge.putEvents(any(PutEventsRequest.class))).thenReturn(accepted());

        LambdaEvents.BatchResponse response = new OutboxRelayHandler(publisher)
                .handleRequest(stream(record("1", "e-1", "QuoteAccepted"), record("2", "e-2", "QuoteCompleted")), null);

        assertThat(response.batchItemFailures()).isEmpty();
        ArgumentCaptor<PutEventsRequest> sent = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(eventBridge, times(2)).putEvents(sent.capture());
        assertThat(sent.getAllValues()).extracting(request -> request.entries().getFirst().detailType())
                .containsExactly("QuoteAccepted", "QuoteCompleted");
        assertThat(sent.getValue().entries().getFirst().source()).isEqualTo("mmda.quote");
        assertThat(sent.getValue().entries().getFirst().eventBusName()).isEqualTo("platform-test-events");
    }

    @Test
    void transientFailureStopsTheBatchAtThatRecordForRedelivery() {
        when(eventBridge.putEvents(any(PutEventsRequest.class)))
                .thenReturn(accepted())
                .thenThrow(SdkClientException.create("connection reset"));

        LambdaEvents.BatchResponse response = new OutboxRelayHandler(publisher).handleRequest(stream(
                record("1", "e-1", "QuoteAccepted"), record("2", "e-2", "QuoteAccepted"),
                record("3", "e-3", "QuoteAccepted")), null);

        assertThat(response.batchItemFailures()).extracting(LambdaEvents.ItemFailure::itemIdentifier)
                .containsExactly("2");
        verify(eventBridge, times(2)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void permanentFailureIsRecordedOnTheItemWithoutFailingTheBatch() {
        when(eventBridge.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder().failedEntryCount(1).entries(PutEventsResultEntry.builder()
                        .errorCode("ValidationException").errorMessage("detail too large").build()).build())
                .thenReturn(accepted());

        LambdaEvents.BatchResponse response = new OutboxRelayHandler(publisher).handleRequest(stream(
                record("1", "e-1", "QuoteAccepted"), record("2", "e-2", "QuoteAccepted")), null);

        assertThat(response.batchItemFailures()).isEmpty();
        ArgumentCaptor<UpdateItemRequest> updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb, times(2)).updateItem(updates.capture());
        UpdateItemRequest failure = updates.getAllValues().getFirst();
        assertThat(failure.key().get("pk").s()).isEqualTo("OUTBOX#e-1");
        assertThat(failure.updateExpression()).contains("attempts").contains("lastError");
        assertThat(failure.expressionAttributeValues().get(":error").s()).startsWith("ValidationException");
        assertThat(failure.updateExpression()).as("goes to the end of the reconciler queue").contains("gsi2sk = :now");
        assertThat(failure.expressionAttributeValues().get(":now").s()).isEqualTo("2026-11-11T10:00:00Z");
        assertThat(failure.conditionExpression()).isEqualTo("attribute_exists(gsi2pk)");
        assertThat(updates.getAllValues().get(1).updateExpression()).startsWith("SET publishedAt");
    }

    @Test
    void throttledEntryIsTransient() {
        when(eventBridge.putEvents(any(PutEventsRequest.class))).thenReturn(PutEventsResponse.builder()
                .failedEntryCount(1).entries(PutEventsResultEntry.builder().errorCode("ThrottlingException").build())
                .build());

        assertThat(publisher.publish(new OutboxPublisher.OutboxItem("e-1", "QuoteAccepted", "{}")))
                .isEqualTo(OutboxPublisher.Result.TRANSIENT_FAILURE);
        verify(dynamoDb, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void serverErrorIsTransientAndClientErrorIsPermanent() {
        when(eventBridge.putEvents(any(PutEventsRequest.class)))
                .thenThrow(serviceError(500, "InternalException"))
                .thenThrow(serviceError(400, "AccessDeniedException"));

        assertThat(publisher.publish(new OutboxPublisher.OutboxItem("e-1", "QuoteAccepted", "{}")))
                .isEqualTo(OutboxPublisher.Result.TRANSIENT_FAILURE);
        assertThat(publisher.publish(new OutboxPublisher.OutboxItem("e-1", "QuoteAccepted", "{}")))
                .isEqualTo(OutboxPublisher.Result.PERMANENT_FAILURE);
    }

    @Test
    void startQuoteRequestedStartsTheExecutionWithTheDeterministicNameAndStableInput() throws Exception {
        when(sfn.startExecution(any(StartExecutionRequest.class))).thenReturn(StartExecutionResponse.builder()
                .executionArn("arn:aws:states:us-east-1:000000000000:execution:quote-flow:q-q1-v1").build());

        assertThat(publisher.publish(startRequested())).isEqualTo(OutboxPublisher.Result.PUBLISHED);

        ArgumentCaptor<StartExecutionRequest> started = ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfn).startExecution(started.capture());
        assertThat(started.getValue().name()).isEqualTo("q-q1-v1");
        assertThat(started.getValue().stateMachineArn()).isEqualTo(STATE_MACHINE);
        assertThat(mapper.readTree(started.getValue().input()))
                .isEqualTo(mapper.readTree("{\"quoteId\":\"q1\",\"version\":1}"));
        verify(eventBridge, never()).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void executionAlreadyExistsCountsAsSuccessAndRecordsTheDerivedArn() {
        when(sfn.startExecution(any(StartExecutionRequest.class)))
                .thenThrow(ExecutionAlreadyExistsException.builder().message("exists").build());

        assertThat(publisher.publish(startRequested())).isEqualTo(OutboxPublisher.Result.PUBLISHED);

        ArgumentCaptor<UpdateItemRequest> updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb, times(2)).updateItem(updates.capture());
        UpdateItemRequest version = updates.getAllValues().getFirst();
        assertThat(version.key().get("sk").s()).isEqualTo("V#0001");
        assertThat(version.expressionAttributeValues().get(":arn").s())
                .isEqualTo("arn:aws:states:us-east-1:000000000000:execution:quote-flow:q-q1-v1");
        assertThat(version.updateExpression()).doesNotContain("revision");
    }

    @Test
    void nonOutboxAndNonInsertRecordsAreSkipped() {
        StreamRecord modify = new StreamRecord("m", "MODIFY", record("1", "e-1", "QuoteAccepted").dynamodb());
        StreamRecord version = new StreamRecord("v", "INSERT", new StreamData(Map.of(
                "type", node("S", "VERSION"), "pk", node("S", "QUOTE#q1")), "9"));

        assertThat(new OutboxRelayHandler(publisher).handleRequest(stream(modify, version), null).batchItemFailures())
                .isEmpty();
        verify(eventBridge, never()).putEvents(any(PutEventsRequest.class));
    }

    private OutboxPublisher.OutboxItem startRequested() {
        return new OutboxPublisher.OutboxItem("e-start", "StartQuoteRequested",
                "{\"eventId\":\"e-start\",\"quoteId\":\"q1\",\"version\":1,\"executionName\":\"q-q1-v1\"}");
    }

    private static PutEventsResponse accepted() {
        return PutEventsResponse.builder().failedEntryCount(0)
                .entries(PutEventsResultEntry.builder().eventId("ok").build()).build();
    }

    private static AwsServiceException serviceError(int status, String code) {
        return AwsServiceException.builder().statusCode(status).message(code)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }

    private StreamRecord record(String sequence, String eventId, String detailType) {
        return new StreamRecord("event-" + sequence, "INSERT", new StreamData(Map.of(
                "pk", node("S", "OUTBOX#" + eventId),
                "sk", node("S", "META"),
                "type", node("S", "OUTBOX"),
                "eventId", node("S", eventId),
                "detailType", node("S", detailType),
                "detail", node("S", "{\"eventId\":\"" + eventId + "\"}"),
                "attempts", node("N", "0")), sequence));
    }

    private JsonNode node(String type, String value) {
        return mapper.createObjectNode().put(type, value);
    }

    private static StreamEvent stream(StreamRecord... records) {
        return new StreamEvent(List.of(records));
    }

    /** Configuração das functions nos testes sem Quarkus. */
    static final class TestConfig implements QuoteFunctionsConfig {

        @Override
        public String tableName() {
            return "quotes-table";
        }

        @Override
        public String eventBusName() {
            return "platform-test-events";
        }

        @Override
        public String stateMachineArn() {
            return STATE_MACHINE;
        }

        @Override
        public String tokenKeySecretId() {
            return "quote-solution/test/token-key";
        }

        @Override
        public int maxInsurers() {
            return 2;
        }

        @Override
        public int outboxRepublishAfterSeconds() {
            return 30;
        }

        @Override
        public int reconcilerBatchSize() {
            return 50;
        }
    }
}

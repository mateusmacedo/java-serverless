package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

@ExtendWith(MockitoExtension.class)
// WHY: a sobrecarga do SDK com Consumer repassa para a sobrecarga com request,
// que é a simulada;
// no modo estrito o Mockito trata essa chamada como argumento divergente
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-11T19:00:00Z");
    private static final OutboxEntry ENTRY = new OutboxEntry("e1", "InsurerResultRecorded", "{\"eventId\":\"e1\"}");

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private EventBridgeClient eventBridge;

    @Mock
    private OutboxStore outbox;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        ProviderProperties properties = new ProviderProperties("table", "bucket", "platform-local-events",
                "mmda.provider", "http://providers", "secret", null, null, 5, 2, 3, 25, 900, 30, "default",
                List.of("alpha"), Map.of());
        publisher = new OutboxPublisher(eventBridge, outbox, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesOnThePlatformBusAndMarksTheItem() {
        givenResponse(PutEventsResponse.builder().failedEntryCount(0)
                .entries(PutEventsResultEntry.builder().eventId("x").build()).build());

        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.PUBLISHED);

        ArgumentCaptor<PutEventsRequest> request = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(eventBridge).putEvents(request.capture());
        assertThat(request.getValue().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.eventBusName()).isEqualTo("platform-local-events");
            assertThat(entry.source()).isEqualTo("mmda.provider");
            assertThat(entry.detailType()).isEqualTo("InsurerResultRecorded");
            assertThat(entry.detail()).isEqualTo("{\"eventId\":\"e1\"}");
        });
        verify(outbox).markPublished("e1", NOW);
    }

    @Test
    void throttledEntryIsTransient() {
        givenResponse(failedEntry("ThrottlingException"));

        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.TRANSIENT_FAILURE);
        verify(outbox, never()).recordFailure(any(), any(), any());
        verify(outbox, never()).markPublished(any(), any());
    }

    @Test
    void rejectedEntryIsPermanentAndRecordedOnTheItem() {
        givenResponse(failedEntry("MalformedDetail"));

        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.PERMANENT_FAILURE);
        verify(outbox).recordFailure(eq("e1"), eq("MalformedDetail: rejected"), eq(NOW));
    }

    @Test
    void clientAndServerErrorsAreTransientButValidationIsPermanent() {
        doThrow(SdkClientException.create("connection reset")).when(eventBridge).putEvents(any(PutEventsRequest.class));
        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.TRANSIENT_FAILURE);

        doThrow(serviceException(500, "InternalException")).when(eventBridge).putEvents(any(PutEventsRequest.class));
        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.TRANSIENT_FAILURE);

        doThrow(serviceException(400, "ValidationException")).when(eventBridge).putEvents(any(PutEventsRequest.class));
        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.PERMANENT_FAILURE);
    }

    @Test
    void publishedButNotMarkedIsTransientSoTheRecordComesBack() {
        givenResponse(PutEventsResponse.builder().failedEntryCount(0).build());
        doThrow(ConditionalCheckFailedException.builder().message("gone").build())
                .when(outbox).markPublished("e1", NOW);

        assertThat(publisher.publish(ENTRY)).isEqualTo(OutboxPublisher.Result.TRANSIENT_FAILURE);
    }

    private void givenResponse(PutEventsResponse response) {
        doReturn(response).when(eventBridge).putEvents(any(PutEventsRequest.class));
    }

    private static PutEventsResponse failedEntry(String errorCode) {
        return PutEventsResponse.builder()
                .failedEntryCount(1)
                .entries(PutEventsResultEntry.builder().errorCode(errorCode).errorMessage("rejected").build())
                .build();
    }

    private static EventBridgeException serviceException(int status, String code) {
        return (EventBridgeException) EventBridgeException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }
}

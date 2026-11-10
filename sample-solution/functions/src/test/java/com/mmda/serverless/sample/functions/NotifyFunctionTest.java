package com.mmda.serverless.sample.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

@ExtendWith(MockitoExtension.class)
// WHY: a sobrecarga do SDK com Consumer repassa para a sobrecarga com request, que é a simulada;
// no modo estrito o Mockito trata essa chamada como argumento divergente
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyFunctionTest {

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private SnsClient sns;

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private EventBridgeClient eventBridge;

    private final ObjectMapper objectMapper = TestFixtures.objectMapper();

    private NotifyFunction function;

    @BeforeEach
    void setUp() {
        function = new NotifyFunction(sns, eventBridge, objectMapper, TestFixtures.PROPERTIES);
        doReturn(PublishResponse.builder().build()).when(sns).publish(any(PublishRequest.class));
    }

    @Test
    void publishesTheRequestToTheTopic() throws Exception {
        givenEventBridgeAccepts();
        RequestContext request = TestFixtures.validatedRequest();

        RequestContext result = function.apply(request);

        assertThat(result).isEqualTo(request);
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(captor.capture());
        assertThat(captor.getValue().topicArn()).isEqualTo("arn:aws:sns:us-east-1:000000000000:notifications");
        assertThat(objectMapper.readValue(captor.getValue().message(), RequestContext.class)).isEqualTo(request);
    }

    @Test
    void emitsRequestCompletedMatchingTheEventBridgeRule() throws Exception {
        givenEventBridgeAccepts();

        function.apply(TestFixtures.validatedRequest());

        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(eventBridge).putEvents(captor.capture());
        assertThat(captor.getValue().entries()).hasSize(1);
        PutEventsRequestEntry entry = captor.getValue().entries().getFirst();
        assertThat(entry.eventBusName()).isEqualTo("events-bus");
        assertThat(entry.source()).isEqualTo("mmda.sample");
        assertThat(entry.detailType()).isEqualTo("RequestCompleted");
        JsonNode detail = objectMapper.readTree(entry.detail());
        assertThat(detail.get("requestId").asText()).isEqualTo("req-1");
        assertThat(detail.get("signature").asText()).isEqualTo("abc123");
    }

    @Test
    void failsWhenEventBridgeRejectsTheEvent() {
        doReturn(PutEventsResponse.builder().failedEntryCount(1).build())
                .when(eventBridge).putEvents(any(PutEventsRequest.class));

        assertThatThrownBy(() -> function.apply(TestFixtures.validatedRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("EventBridge rejected the event");
    }

    private void givenEventBridgeAccepts() {
        doReturn(PutEventsResponse.builder().failedEntryCount(0).build())
                .when(eventBridge).putEvents(any(PutEventsRequest.class));
    }
}

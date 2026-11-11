package com.mmda.serverless.receipt.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

@ExtendWith(MockitoExtension.class)
class AnnounceReceiptHandlerTest {

    private static final Receipt RECEIPT = new Receipt("req-1", "abc123", 42L, "2026-11-10T22:17:40Z");

    @Mock
    private EventBridgeClient eventBridge;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AnnounceReceiptHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AnnounceReceiptHandler(eventBridge, objectMapper, TestFixtures.CONFIG);
    }

    @Test
    void publishesReceiptIssuedWithTheReceiptAsDetail() throws Exception {
        doReturn(PutEventsResponse.builder().failedEntryCount(0).build())
                .when(eventBridge).putEvents(any(PutEventsRequest.class));

        Receipt result = handler.handleRequest(RECEIPT, null);

        assertThat(result).isEqualTo(RECEIPT);
        ArgumentCaptor<PutEventsRequest> captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(eventBridge).putEvents(captor.capture());
        PutEventsRequestEntry entry = captor.getValue().entries().getFirst();
        assertThat(entry.eventBusName()).isEqualTo("receipts-bus");
        assertThat(entry.source()).isEqualTo("mmda.receipt");
        assertThat(entry.detailType()).isEqualTo("ReceiptIssued");
        assertThat(objectMapper.readValue(entry.detail(), Receipt.class)).isEqualTo(RECEIPT);
    }

    @Test
    void failsWhenEventBridgeRejectsTheEvent() {
        doReturn(PutEventsResponse.builder().failedEntryCount(1).build())
                .when(eventBridge).putEvents(any(PutEventsRequest.class));

        assertThatThrownBy(() -> handler.handleRequest(RECEIPT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("EventBridge rejected the event");
    }
}

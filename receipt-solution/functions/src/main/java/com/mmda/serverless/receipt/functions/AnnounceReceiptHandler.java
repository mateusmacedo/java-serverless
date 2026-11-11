package com.mmda.serverless.receipt.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.UncheckedIOException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/**
 * Publica {@code ReceiptIssued} no barramento da receipt-solution, para que outras solutions
 * reajam ao comprovante sem depender desta.
 */
@Singleton
@Named("announce")
public class AnnounceReceiptHandler implements RequestHandler<Receipt, Receipt> {

    static final String EVENT_SOURCE = "mmda.receipt";
    static final String EVENT_DETAIL_TYPE = "ReceiptIssued";

    private final EventBridgeClient eventBridge;
    private final ObjectMapper objectMapper;
    private final ReceiptConfig config;

    AnnounceReceiptHandler(EventBridgeClient eventBridge, ObjectMapper objectMapper, ReceiptConfig config) {
        this.eventBridge = eventBridge;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    @Override
    public Receipt handleRequest(Receipt receipt, Context context) {
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(config.eventBusName())
                .source(EVENT_SOURCE)
                .detailType(EVENT_DETAIL_TYPE)
                .detail(toJson(receipt))
                .build();
        PutEventsResponse response = eventBridge.putEvents(PutEventsRequest.builder().entries(entry).build());
        if (response.failedEntryCount() > 0) {
            throw new IllegalStateException("EventBridge rejected the event: " + response.entries());
        }
        return receipt;
    }

    private String toJson(Receipt receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

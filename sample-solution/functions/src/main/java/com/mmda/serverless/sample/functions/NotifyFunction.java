package com.mmda.serverless.sample.functions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Publica no SNS e emite o evento no EventBridge. A origem e o tipo do evento precisam bater
 * com o padrão da regra declarada no serverless.yml.
 */
@Component("notify")
class NotifyFunction implements Function<RequestContext, RequestContext> {

    static final String EVENT_SOURCE = "mmda.sample";
    static final String EVENT_DETAIL_TYPE = "RequestCompleted";

    private final SnsClient sns;
    private final EventBridgeClient eventBridge;
    private final ObjectMapper objectMapper;
    private final FunctionsProperties properties;

    NotifyFunction(SnsClient sns, EventBridgeClient eventBridge, ObjectMapper objectMapper,
                   FunctionsProperties properties) {
        this.sns = sns;
        this.eventBridge = eventBridge;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public RequestContext apply(RequestContext request) {
        String json = toJson(request);

        sns.publish(builder -> builder.topicArn(properties.topicArn()).message(json));

        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(properties.eventBusName())
                .source(EVENT_SOURCE)
                .detailType(EVENT_DETAIL_TYPE)
                .detail(json)
                .build();
        PutEventsResponse response = eventBridge.putEvents(builder -> builder.entries(entry));
        if (response.failedEntryCount() > 0) {
            throw new IllegalStateException("EventBridge rejected the event: " + response.entries());
        }
        return request;
    }

    private String toJson(RequestContext request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

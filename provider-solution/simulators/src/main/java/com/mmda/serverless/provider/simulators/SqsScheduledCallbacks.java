package com.mmda.serverless.provider.simulators;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

/** Callbacks na fila {@code sim-callbacks}, com o atraso no {@code DelaySeconds} da mensagem. */
@Component
class SqsScheduledCallbacks implements ScheduledCallbacks {

    private static final int MAX_DELAY_SECONDS = 900;

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final SimulatorProperties properties;

    SqsScheduledCallbacks(SqsClient sqs, ObjectMapper objectMapper, SimulatorProperties properties) {
        this.sqs = sqs;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void schedule(CallbackOrder order, int delaySeconds) {
        sqs.sendMessage(builder -> builder
                .queueUrl(properties.simCallbacksQueueUrl())
                .messageBody(objectMapper.writeValueAsString(order))
                .delaySeconds(Math.clamp(delaySeconds, 0, MAX_DELAY_SECONDS)));
    }
}

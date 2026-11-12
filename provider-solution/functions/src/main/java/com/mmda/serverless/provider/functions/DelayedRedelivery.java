package com.mmda.serverless.provider.functions;

import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Sem quota, o worker reenvia o pedido com {@code DelaySeconds} até a virada da janela e deixa a original
 * ser apagada, para não gastar {@code maxReceiveCount} (§9). O reenvio preserva o {@code dlqAttempt}.
 */
@Component
class DelayedRedelivery {

    static final int MAX_DELAY_SECONDS = 900;

    private final SqsClient sqs;

    DelayedRedelivery(SqsClient sqs) {
        this.sqs = sqs;
    }

    void send(String queueUrl, QueuedMessage message, int delaySeconds) {
        sqs.sendMessage(builder -> {
            builder.queueUrl(queueUrl)
                    .messageBody(message.body())
                    .delaySeconds(Math.clamp(delaySeconds, 0, MAX_DELAY_SECONDS));
            if (message.dlqAttempt() != null) {
                builder.messageAttributes(Map.of(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE,
                        MessageAttributeValue.builder().dataType("Number").stringValue(message.dlqAttempt()).build()));
            }
        });
    }
}

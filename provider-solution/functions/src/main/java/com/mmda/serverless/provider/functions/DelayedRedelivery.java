package com.mmda.serverless.provider.functions;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Sem quota, o worker reenvia o pedido com {@code DelaySeconds} até a virada da janela e deixa a original
 * ser apagada, para não gastar {@code maxReceiveCount} (§9).
 */
@Component
class DelayedRedelivery {

    static final int MAX_DELAY_SECONDS = 900;

    private final SqsClient sqs;

    DelayedRedelivery(SqsClient sqs) {
        this.sqs = sqs;
    }

    void send(String queueUrl, String messageBody, int delaySeconds) {
        sqs.sendMessage(builder -> builder
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .delaySeconds(Math.clamp(delaySeconds, 0, MAX_DELAY_SECONDS)));
    }
}

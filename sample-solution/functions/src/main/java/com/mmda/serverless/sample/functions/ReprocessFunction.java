package com.mmda.serverless.sample.functions;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * Lê a DLQ e reinicia a máquina de estado para cada mensagem. Depois de {@code maxAttempts}
 * tentativas a mensagem é descartada, para um payload sempre inválido não circular para sempre.
 */
@Component("reprocess")
class ReprocessFunction implements Function<ReprocessCommand, ReprocessResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReprocessFunction.class);

    private final SqsClient sqs;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final FunctionsProperties properties;

    ReprocessFunction(SqsClient sqs, SfnClient sfn, ObjectMapper objectMapper, FunctionsProperties properties) {
        this.sqs = sqs;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public ReprocessResult apply(ReprocessCommand command) {
        String queueUrl = sqs.getQueueUrl(builder -> builder.queueName(properties.deadLetterQueueName())).queueUrl();
        int restarted = 0;
        int discarded = 0;

        for (Message message : sqs.receiveMessage(builder -> builder
                .queueUrl(queueUrl)
                .maxNumberOfMessages(command.batchSize())).messages()) {
            RequestContext failed = objectMapper.readValue(message.body(), RequestContext.class);
            if (failed.attempt() >= properties.maxAttempts()) {
                LOGGER.warn("Discarding request {} after {} attempts", failed.requestId(), failed.attempt());
                discarded++;
            } else {
                restart(failed.nextAttempt());
                restarted++;
            }
            sqs.deleteMessage(builder -> builder.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
        return new ReprocessResult(restarted, discarded);
    }

    private void restart(RequestContext retry) {
        String input = objectMapper.writeValueAsString(retry);
        sfn.startExecution(builder -> builder
                .stateMachineArn(properties.stateMachineArn())
                .name(retry.requestId() + "-" + retry.attempt())
                .input(input));
    }
}

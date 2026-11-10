package com.mmda.serverless.sample.functions;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sample")
public record FunctionsProperties(
        String bucketName,
        String tableName,
        String topicArn,
        String eventBusName,
        String deadLetterQueueName,
        String stateMachineArn,
        String maxPayloadParameter,
        String signingSecretId,
        int maxAttempts) {
}

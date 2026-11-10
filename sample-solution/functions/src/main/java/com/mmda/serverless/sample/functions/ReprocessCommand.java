package com.mmda.serverless.sample.functions;

public record ReprocessCommand(Integer maxMessages) {

    private static final int SQS_MAX_BATCH = 10;

    int batchSize() {
        return maxMessages == null ? SQS_MAX_BATCH : Math.clamp(maxMessages, 1, SQS_MAX_BATCH);
    }
}

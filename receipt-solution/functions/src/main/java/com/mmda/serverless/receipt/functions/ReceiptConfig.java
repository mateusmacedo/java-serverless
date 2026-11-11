package com.mmda.serverless.receipt.functions;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "receipt")
public interface ReceiptConfig {

    String tableName();

    String eventBusName();
}

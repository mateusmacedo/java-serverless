package com.mmda.serverless.receipt.api;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "receipt")
public interface ReceiptApiConfig {

    String tableName();

    String stateMachineArn();
}

package com.mmda.serverless.sample.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sample")
public record ApiProperties(String bucketName, String stateMachineArn) {
}

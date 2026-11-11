package com.mmda.serverless.platform.spring.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "mmda.aws")
public record AwsClientsProperties(@DefaultValue S3 s3) {

    /**
     * @param pathStyleAccess usa URLs no formato host/bucket/chave; necessário quando o
     *                        host do emulador não resolve subdomínios por bucket
     */
    public record S3(boolean pathStyleAccess) {
    }
}

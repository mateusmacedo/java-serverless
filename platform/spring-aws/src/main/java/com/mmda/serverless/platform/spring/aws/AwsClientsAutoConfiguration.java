package com.mmda.serverless.platform.spring.aws;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Clients síncronos do AWS SDK v2. Região, credenciais e endpoint vêm das cadeias padrão
 * do SDK (incluindo {@code AWS_ENDPOINT_URL}), então o mesmo código roda no Floci e na AWS.
 * <p>
 * WHY: cada client recebe o próprio {@link AwsCrtHttpClient} pelo builder, como recomenda a AWS,
 * e só é criado se o módulo declarar o SDK correspondente.
 */
@AutoConfiguration
@EnableConfigurationProperties(AwsClientsProperties.class)
public class AwsClientsAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DynamoDbClient.class)
    static class DynamoDbClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DynamoDbClient dynamoDbClient() {
            return DynamoDbClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SqsClient.class)
    static class SqsClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SqsClient sqsClient() {
            return SqsClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SnsClient.class)
    static class SnsClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SnsClient snsClient() {
            return SnsClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EventBridgeClient.class)
    static class EventBridgeClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        EventBridgeClient eventBridgeClient() {
            return EventBridgeClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(S3Client.class)
    static class S3ClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        S3Client s3Client(AwsClientsProperties properties) {
            return S3Client.builder()
                    .httpClientBuilder(AwsCrtHttpClient.builder())
                    .forcePathStyle(properties.s3().pathStyleAccess())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecretsManagerClient.class)
    static class SecretsManagerClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SecretsManagerClient secretsManagerClient() {
            return SecretsManagerClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SsmClient.class)
    static class SsmClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SsmClient ssmClient() {
            return SsmClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SchedulerClient.class)
    static class SchedulerClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SchedulerClient schedulerClient() {
            return SchedulerClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SfnClient.class)
    static class SfnClientConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SfnClient sfnClient() {
            return SfnClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
        }
    }
}

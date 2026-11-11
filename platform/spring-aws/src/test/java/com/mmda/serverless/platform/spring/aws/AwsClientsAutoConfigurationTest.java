package com.mmda.serverless.platform.spring.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

class AwsClientsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsClientsAutoConfiguration.class))
            .withSystemProperties("aws.region=us-east-1", "aws.accessKeyId=test", "aws.secretAccessKey=test");

    @Test
    void createsAClientForEverySdkOnTheClasspath() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(DynamoDbClient.class)
                .hasSingleBean(SqsClient.class)
                .hasSingleBean(SnsClient.class)
                .hasSingleBean(EventBridgeClient.class)
                .hasSingleBean(S3Client.class)
                .hasSingleBean(SecretsManagerClient.class)
                .hasSingleBean(SsmClient.class)
                .hasSingleBean(SchedulerClient.class)
                .hasSingleBean(SfnClient.class));
    }

    @Test
    void skipsTheClientWhenItsSdkIsMissing() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(DynamoDbClient.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DynamoDbClient.class)
                        .hasSingleBean(S3Client.class));
    }

    @Test
    void keepsAClientDefinedByTheApplication() {
        S3Client custom = S3Client.builder().region(Region.US_EAST_1).build();

        contextRunner
                .withBean(S3Client.class, () -> custom)
                .run(context -> assertThat(context.getBean(S3Client.class)).isSameAs(custom));
    }

    @Test
    void usesVirtualHostedStyleUrlsByDefault() {
        contextRunner.run(context -> assertThat(objectUrl(context.getBean(S3Client.class)))
                .hasHost("bucket.s3.amazonaws.com")
                .hasPath("/key"));
    }

    @Test
    void usesPathStyleUrlsWhenConfigured() {
        contextRunner
                .withPropertyValues("mmda.aws.s3.path-style-access=true")
                .run(context -> assertThat(objectUrl(context.getBean(S3Client.class)))
                        .hasHost("s3.amazonaws.com")
                        .hasPath("/bucket/key"));
    }

    private static URL objectUrl(S3Client s3) {
        return s3.utilities().getUrl(builder -> builder.bucket("bucket").key("key"));
    }
}

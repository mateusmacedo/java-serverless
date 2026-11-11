package com.mmda.serverless.receipt.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

@QuarkusTest
@WithTestResource(FlociResource.class)
class ReceiptFunctionsIT {

    @Inject
    IssueReceiptHandler issue;

    @Inject
    AnnounceReceiptHandler announce;

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "it.capture-queue-url")
    String captureQueueUrl;

    @Test
    void issueStoresTheReceiptAndReturnsTheSameOneWhenTheEventRepeats() {
        CompletedRequest request = new CompletedRequest("it-issue", "sig-1", 31L);

        Receipt first = issue.handleRequest(request, null);
        Receipt repeated = issue.handleRequest(request, null);

        assertThat(repeated).isEqualTo(first);
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder
                .tableName(FlociResource.TABLE)
                .key(Map.of("requestId", AttributeValue.fromS("it-issue")))).item();
        assertThat(Receipt.fromItem(item)).isEqualTo(first);
    }

    @Test
    void announcePublishesReceiptIssuedOnTheReceiptBus() throws Exception {
        Receipt receipt = new Receipt("it-announce", "sig-2", 64L, "2026-11-10T22:17:40Z");

        announce.handleRequest(receipt, null);

        JsonNode event = objectMapper.readTree(awaitCapturedEvent().body());
        assertThat(event.get("source").asText()).isEqualTo("mmda.receipt");
        assertThat(event.get("detail-type").asText()).isEqualTo("ReceiptIssued");
        assertThat(objectMapper.treeToValue(event.get("detail"), Receipt.class)).isEqualTo(receipt);
    }

    private Message awaitCapturedEvent() {
        try (SqsClient sqs = SqsClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build()) {
            return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                    .until(() -> sqs.receiveMessage(builder -> builder.queueUrl(captureQueueUrl).maxNumberOfMessages(1))
                                    .messages().stream().findFirst().orElse(null),
                            Objects::nonNull);
        }
    }
}

package com.mmda.serverless.receipt.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

@ExtendWith(MockitoExtension.class)
class IssueReceiptHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T22:17:40Z");
    private static final CompletedRequest REQUEST = new CompletedRequest("req-1", "abc123", 42L);

    @Mock
    private DynamoDbClient dynamoDb;

    private IssueReceiptHandler handler;

    @BeforeEach
    void setUp() {
        handler = new IssueReceiptHandler(dynamoDb, TestFixtures.CONFIG, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesANewReceiptOnlyIfTheRequestHasNoneYet() {
        doReturn(PutItemResponse.builder().build()).when(dynamoDb).putItem(any(PutItemRequest.class));

        Receipt receipt = handler.handleRequest(REQUEST, null);

        assertThat(receipt).isEqualTo(new Receipt("req-1", "abc123", 42L, "2026-11-10T22:17:40Z"));
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("receipts-table");
        assertThat(captor.getValue().conditionExpression()).isEqualTo("attribute_not_exists(requestId)");
        assertThat(Receipt.fromItem(captor.getValue().item())).isEqualTo(receipt);
    }

    @Test
    void returnsTheStoredReceiptWhenTheEventIsDeliveredAgain() {
        Receipt stored = new Receipt("req-1", "abc123", 42L, "2026-11-10T22:17:39Z");
        doThrow(ConditionalCheckFailedException.builder().message("exists").build())
                .when(dynamoDb).putItem(any(PutItemRequest.class));
        doReturn(GetItemResponse.builder().item(stored.toItem()).build())
                .when(dynamoDb).getItem(any(GetItemRequest.class));

        Receipt receipt = handler.handleRequest(REQUEST, null);

        assertThat(receipt).isEqualTo(stored);
        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().key().get("requestId").s()).isEqualTo("req-1");
    }
}

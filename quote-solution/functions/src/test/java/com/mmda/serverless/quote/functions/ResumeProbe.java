package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.WorkRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * {@link ResultResumer} de um pedido sobre DynamoDB e Step Functions simulados: o waiter do pedido fica
 * {@code WAITING}, as transações são aceitas e registradas para conferir o que o {@code resume} grava.
 */
final class ResumeProbe {

    static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
    private final WorkRef workRef;
    private final ResultResumer resumer;

    /** Waiter do enriquecimento de {@code quoteId}; {@code expiresAt} vencido leva o resultado a {@code LATE}. */
    ResumeProbe(WorkRef workRef, String quoteId, int version, Instant expiresAt) {
        this(workRef, WorkRef.Kind.ENRICHMENT, null, quoteId, version, expiresAt);
    }

    /** Waiter de um pedido do tipo {@code kind}; {@code insurerId} só existe no pedido de seguradora. */
    ResumeProbe(WorkRef workRef, WorkRef.Kind kind, String insurerId, String quoteId, int version, Instant expiresAt) {
        this.workRef = workRef;
        TokenCipher cipher = new TokenCipher(() -> "token-key");
        resumer = new ResultResumer(new QuoteTable(dynamoDb, new OutboxPublishingTest.TestConfig(), mapper), cipher,
                mock(SfnClient.class), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, AttributeValue> waiter = new HashMap<>(Map.of(
                QuoteKeys.PK, AttributeValue.fromS(QuoteKeys.waiterPk(workRef)),
                QuoteKeys.SK, AttributeValue.fromS(QuoteKeys.META),
                "quoteId", AttributeValue.fromS(quoteId),
                "version", AttributeValue.fromN(Integer.toString(version)),
                "kind", AttributeValue.fromS(kind.name()),
                "status", AttributeValue.fromS("WAITING"),
                "tokenCiphertext", AttributeValue.fromS(cipher.encrypt("task-token", workRef)),
                "expiresAt", AttributeValue.fromS(expiresAt.toString())));
        if (insurerId != null) {
            waiter.put("insurerId", AttributeValue.fromS(insurerId));
        }
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            boolean isWaiter = request.key().get(QuoteKeys.PK).s().equals(QuoteKeys.waiterPk(workRef));
            return GetItemResponse.builder().item(isWaiter ? waiter : null).build();
        });
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());
    }

    ResultResumer resumer() {
        return resumer;
    }

    ResultResumer.Disposition resume(String body) {
        return resumer.handle(body);
    }

    /** Itens gravados com {@code Put} em qualquer transação, com o {@code type} informado. */
    List<Map<String, AttributeValue>> puts(String type) {
        return writes().stream()
                .map(TransactWriteItem::put)
                .filter(Objects::nonNull)
                .map(Put::item)
                .filter(item -> item.containsKey(QuoteKeys.TYPE) && type.equals(item.get(QuoteKeys.TYPE).s()))
                .toList();
    }

    Map<String, AttributeValue> put(String type) {
        List<Map<String, AttributeValue>> items = puts(type);
        assertThat(items).as("items of type %s", type).hasSize(1);
        return items.getFirst();
    }

    /** {@code Update} que uma transação aplicou ao item {@code pk}/{@code sk}. */
    Update update(String pk, String sk) {
        List<Update> updates = writes().stream()
                .map(TransactWriteItem::update)
                .filter(Objects::nonNull)
                .filter(candidate -> pk.equals(candidate.key().get(QuoteKeys.PK).s())
                        && sk.equals(candidate.key().get(QuoteKeys.SK).s()))
                .toList();
        assertThat(updates).as("updates of %s %s", pk, sk).hasSize(1);
        return updates.getFirst();
    }

    /** {@code resumePayload} que a transação grava no waiter e o {@code SendTaskSuccess} devolve à máquina. */
    JsonNode resumePayload() {
        Update update = writes().stream()
                .map(TransactWriteItem::update)
                .filter(Objects::nonNull)
                .filter(candidate -> QuoteKeys.waiterPk(workRef).equals(candidate.key().get(QuoteKeys.PK).s()))
                .findFirst()
                .orElseThrow();
        try {
            return mapper.readTree(update.expressionAttributeValues().get(":payload").s());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    List<TransactWriteItem> writes() {
        ArgumentCaptor<TransactWriteItemsRequest> requests = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, atLeast(0)).transactWriteItems(requests.capture());
        return requests.getAllValues().stream().flatMap(request -> request.transactItems().stream()).toList();
    }

    static JsonNode summary(Map<String, AttributeValue> evidence) {
        try {
            return new ObjectMapper().readTree(evidence.get("summary").s());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

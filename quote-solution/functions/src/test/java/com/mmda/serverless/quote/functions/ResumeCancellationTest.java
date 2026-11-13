package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.WorkRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Cancelamentos que o Floci não emula (§9 e §13 do desenho): só a falha de
 * condição leva a resultado tardio
 * ou repetido; throttling e {@code TransactionConflict} que sobrou das
 * repetições devolvem a mensagem à fila.
 */
class ResumeCancellationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String QUOTE_ID = "5b0b6c1e-5f1d-4a8e-9a39-2f1f5d6e7c11";
    private static final WorkRef WORK_REF = WorkRef.of(QUOTE_ID, 1, WorkRef.Kind.INSURER, "alpha");

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbClient dynamoDb;
    private SfnClient sfn;
    private QuoteTable table;
    private ResultResumer resumer;

    @BeforeEach
    void setUp() {
        dynamoDb = mock(DynamoDbClient.class);
        sfn = mock(SfnClient.class);
        table = new QuoteTable(dynamoDb, new OutboxPublishingTest.TestConfig(), mapper);
        resumer = new ResultResumer(table, new TokenCipher(() -> "token-key"), sfn, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            boolean waiter = request.key().get(QuoteKeys.PK).s().equals(QuoteKeys.waiterPk(WORK_REF));
            return GetItemResponse.builder().item(waiter ? waitingWaiter() : null).build();
        });
    }

    @Test
    void throttledResumeIsRedeliveredInsteadOfBecomingLate() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(canceled("ThrottlingError"));
        String message = QuoteFixtures.insurerResult(WORK_REF.value(), QUOTE_ID, "QUOTED",
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> resumer.handle(message)).isInstanceOf(TransactionCanceledException.class);

        verify(dynamoDb, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
        verifyNoInteractions(sfn);
        LambdaEvents.BatchResponse response = new ResumeHandler(resumer).handleRequest(QuoteFixtures.sqs(message),
                null);
        assertThat(response.batchItemFailures()).hasSize(1);
    }

    @Test
    void transactionConflictLeftAfterTheRetriesIsRedeliveredWithoutLateEvidence() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(canceled("TransactionConflict"));
        String message = QuoteFixtures.insurerResult(WORK_REF.value(), QUOTE_ID, "QUOTED",
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> resumer.handle(message)).isInstanceOf(TransactionCanceledException.class);

        ArgumentCaptor<TransactWriteItemsRequest> transactions = ArgumentCaptor
                .forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb, times(4)).transactWriteItems(transactions.capture());
        assertThat(transactions.getAllValues()).flatExtracting(TransactWriteItemsRequest::transactItems)
                .extracting(TransactWriteItem::put)
                .filteredOn(put -> put != null && put.item().containsKey(QuoteKeys.TYPE))
                .extracting(put -> put.item().get(QuoteKeys.TYPE).s())
                .doesNotContain(QuoteKeys.TYPE_LATE);
    }

    @Test
    void conditionFailureStillBecomesLateEvidence() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(canceled("ConditionalCheckFailed"))
                .thenReturn(TransactWriteItemsResponse.builder().build());
        String message = QuoteFixtures.insurerResult(WORK_REF.value(), QUOTE_ID, "QUOTED",
                UUID.randomUUID().toString());

        assertThat(resumer.handle(message)).isEqualTo(ResultResumer.Disposition.LATE);
    }

    @Test
    void throttledLateEvidenceIsRedeliveredInsteadOfDiscarded() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(canceled("ConditionalCheckFailed"))
                .thenThrow(canceled("ThrottlingError"));
        String message = QuoteFixtures.insurerResult(WORK_REF.value(), QUOTE_ID, "QUOTED",
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> resumer.handle(message)).isInstanceOf(TransactionCanceledException.class);
    }

    @Test
    void summaryFailureAfterTheCommitDoesNotFailTheStep() {
        doThrow(ProvisionedThroughputExceededException.builder().message("throttled").build())
                .when(dynamoDb).getItem(any(GetItemRequest.class));

        table.refreshSummary(QUOTE_ID, 1, NOW);

        verify(dynamoDb).getItem(any(GetItemRequest.class));
    }

    private static Map<String, AttributeValue> waitingWaiter() {
        return Map.of(
                QuoteKeys.PK, AttributeValue.fromS(QuoteKeys.waiterPk(WORK_REF)),
                QuoteKeys.SK, AttributeValue.fromS(QuoteKeys.META),
                "quoteId", AttributeValue.fromS(QUOTE_ID),
                "version", AttributeValue.fromN("1"),
                "kind", AttributeValue.fromS("INSURER"),
                "insurerId", AttributeValue.fromS("alpha"),
                "status", AttributeValue.fromS("WAITING"),
                "expiresAt", AttributeValue.fromS(NOW.plusSeconds(20).toString()));
    }

    private static TransactionCanceledException canceled(String reason) {
        return TransactionCanceledException.builder().message("canceled").cancellationReasons(List.of(
                CancellationReason.builder().code(reason).build(),
                CancellationReason.builder().code("None").build())).build();
    }
}

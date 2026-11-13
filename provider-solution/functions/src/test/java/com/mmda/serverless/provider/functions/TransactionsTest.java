package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** {@code TransactionConflict} não é emulado pelo Floci (§13); este caminho é coberto aqui e repetido no M6. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionsTest {

    private static final List<TransactWriteItem> ITEMS = List.of(TransactWriteItem.builder().build());

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private DynamoDbClient dynamoDb;

    private final List<Duration> sleeps = new ArrayList<>();

    @Test
    void conflictIsRetriedWithBackoffUntilItCommits() {
        doThrow(cancelled("TransactionConflict", "None"))
                .doThrow(cancelled("None", "TransactionConflict"))
                .doReturn(TransactWriteItemsResponse.builder().build())
                .when(dynamoDb).transactWriteItems(any(TransactWriteItemsRequest.class));

        Transactions.Result result = transactions().write(ITEMS);

        assertThat(result.committed()).isTrue();
        assertThat(sleeps).hasSize(2).allSatisfy(sleep -> assertThat(sleep).isPositive());
        verify(dynamoDb, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void conflictThatPersistsIsRaisedForTheMessageToBeRedelivered() {
        doThrow(cancelled("TransactionConflict")).when(dynamoDb).transactWriteItems(any(TransactWriteItemsRequest.class));

        assertThatThrownBy(() -> transactions().write(ITEMS)).isInstanceOf(TransactionCanceledException.class);
        verify(dynamoDb, times(Transactions.MAX_CONFLICT_RETRIES + 1))
                .transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void failedConditionIsAResultWithTheReasonOfEachItem() {
        doThrow(cancelled("None", "ConditionalCheckFailed")).when(dynamoDb)
                .transactWriteItems(any(TransactWriteItemsRequest.class));

        Transactions.Result result = transactions().write(ITEMS);

        assertThat(result.committed()).isFalse();
        assertThat(result.conditionFailedAt(0)).isFalse();
        assertThat(result.conditionFailedAt(1)).isTrue();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void committedTransactionHasNoFailedCondition() {
        doReturn(TransactWriteItemsResponse.builder().build()).when(dynamoDb)
                .transactWriteItems(any(TransactWriteItemsRequest.class));

        assertThat(transactions().write(ITEMS).conditionFailedAt(0)).isFalse();
    }

    private Transactions transactions() {
        return new Transactions(dynamoDb, sleeps::add, RandomGenerator.getDefault());
    }

    private static TransactionCanceledException cancelled(String... codes) {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(java.util.Arrays.stream(codes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }
}

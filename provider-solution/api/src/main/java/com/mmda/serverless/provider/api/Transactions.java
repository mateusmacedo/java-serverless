package com.mmda.serverless.provider.api;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * {@code TransactWriteItems} com o tratamento por motivo de cancelamento, como
 * nos workers. WHY:
 * {@code TransactionConflict} é repetido aqui, com backoff e jitter, porque o
 * SDK não repete esse caso (§9).
 */
@Component
class Transactions {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final String TRANSACTION_CONFLICT = "TransactionConflict";
    private static final int MAX_CONFLICT_RETRIES = 3;
    private static final long CONFLICT_BACKOFF_MILLIS = 50;
    private static final Logger LOGGER = LoggerFactory.getLogger(Transactions.class);

    private final DynamoDbClient dynamoDb;

    Transactions(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    /**
     * Grava tudo ou nada; um cancelamento por condição volta como resultado, não
     * como exceção.
     */
    Result write(List<TransactWriteItem> items) {
        for (int conflicts = 0;; conflicts++) {
            try {
                dynamoDb.transactWriteItems(builder -> builder.transactItems(items));
                return Result.COMMITTED;
            } catch (TransactionCanceledException exception) {
                List<String> reasons = exception.cancellationReasons().stream()
                        .map(CancellationReason::code)
                        .toList();
                if (reasons.contains(CONDITIONAL_CHECK_FAILED)) {
                    return new Result(false, reasons);
                }
                if (!reasons.contains(TRANSACTION_CONFLICT) || conflicts >= MAX_CONFLICT_RETRIES) {
                    throw exception;
                }
                LOGGER.warn("Transaction conflict, retrying ({} of {})", conflicts + 1, MAX_CONFLICT_RETRIES);
                pause(conflicts);
            }
        }
    }

    private static void pause(int conflicts) {
        long ceiling = CONFLICT_BACKOFF_MILLIS << conflicts;
        try {
            Thread.sleep(Duration.ofMillis(ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during the transaction backoff", exception);
        }
    }

    /**
     * @param reasons motivo de cada item, na ordem da transação, quando ela foi
     *                cancelada
     */
    record Result(boolean committed, List<String> reasons) {

        static final Result COMMITTED = new Result(true, List.of());

        boolean conditionFailedAt(int index) {
            return !committed && index < reasons.size() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index));
        }
    }
}

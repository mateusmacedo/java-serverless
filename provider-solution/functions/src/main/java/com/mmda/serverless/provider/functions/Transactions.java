package com.mmda.serverless.provider.functions;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * {@code TransactWriteItems} com o tratamento por motivo de cancelamento. {@code TransactionConflict} é
 * repetido aqui, com backoff e jitter, porque o SDK não repete esse caso e o Floci não o emula (§9).
 */
@Component
class Transactions {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    static final String TRANSACTION_CONFLICT = "TransactionConflict";
    static final int MAX_CONFLICT_RETRIES = 3;
    private static final long CONFLICT_BACKOFF_MILLIS = 50;

    private static final Logger LOGGER = LoggerFactory.getLogger(Transactions.class);

    private final DynamoDbClient dynamoDb;
    private final Sleeper sleeper;
    private final RandomGenerator jitter;

    Transactions(DynamoDbClient dynamoDb, Sleeper sleeper, RandomGenerator jitter) {
        this.dynamoDb = dynamoDb;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    /** Grava tudo ou nada; um cancelamento por condição volta como resultado, não como exceção. */
    Result write(List<TransactWriteItem> items) {
        for (int conflicts = 0; ; conflicts++) {
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
                long ceiling = CONFLICT_BACKOFF_MILLIS << conflicts;
                LOGGER.warn("Transaction conflict, retrying ({} of {})", conflicts + 1, MAX_CONFLICT_RETRIES);
                sleeper.sleep(Duration.ofMillis(ceiling / 2 + (long) (jitter.nextDouble() * ceiling / 2)));
            }
        }
    }

    /** @param reasons motivo de cada item, na ordem da transação, quando ela foi cancelada */
    record Result(boolean committed, List<String> reasons) {

        static final Result COMMITTED = new Result(true, List.of());

        boolean conditionFailedAt(int index) {
            return !committed && index < reasons.size() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index));
        }
    }
}

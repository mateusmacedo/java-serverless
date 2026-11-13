package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.GSI2PK;
import static com.mmda.serverless.provider.functions.Items.GSI2SK;
import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.n;
import static com.mmda.serverless.provider.functions.Items.putIfPresent;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Consultas, tentativas, evidências, quota e {@code CONSUMED} na tabela da provider. Toda gravação que
 * precisa ser atômica com o resultado passa por uma só transação (desenho, §8).
 */
@Component
class QueryLedger {

    private static final String STATE = "#state";
    private static final Map<String, String> STATE_NAME = Map.of(STATE, "state");

    private final DynamoDbClient dynamoDb;
    private final Transactions transactions;
    private final OutboxStore outbox;
    private final ProviderProperties properties;

    QueryLedger(DynamoDbClient dynamoDb, Transactions transactions, OutboxStore outbox, ProviderProperties properties) {
        this.dynamoDb = dynamoDb;
        this.transactions = transactions;
        this.outbox = outbox;
        this.properties = properties;
    }

    enum Reservation {
        RESERVED,
        /** A consulta já existia: outra entrega do mesmo pedido chegou antes. */
        QUERY_EXISTS,
        /** A consulta não está mais em voo. */
        NOT_IN_FLIGHT,
        QUOTA_EXHAUSTED
    }

    Optional<QueryRecord> load(String queryId) {
        List<Map<String, AttributeValue>> items = dynamoDb.query(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", s(Items.queryPk(queryId)))))
                .items();
        Map<String, AttributeValue> meta = null;
        List<QueryRecord.Attempt> attempts = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) {
            String sk = item.get(Items.SK).s();
            if (META.equals(sk)) {
                meta = item;
            } else if (sk.startsWith("ATTEMPT#")) {
                attempts.add(new QueryRecord.Attempt(
                        Items.number(item, "sequence").orElseThrow().intValue(),
                        Items.string(item, "startedAt").orElse(null),
                        Items.string(item, "finishedAt").orElse(null),
                        Items.string(item, "outcome").orElse(null)));
            }
        }
        if (meta == null) {
            return Optional.empty();
        }
        attempts.sort(Comparator.comparingInt(QueryRecord.Attempt::sequence));
        return Optional.of(new QueryRecord(queryId,
                QueryState.valueOf(Items.string(meta, "state").orElseThrow()),
                Items.string(meta, "providerRequestId").orElse(null),
                attempts));
    }

    boolean isConsumed(String eventId) {
        return dynamoDb.getItem(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .key(Items.key(Items.consumedPk(eventId), META)))
                .hasItem();
    }

    /** Antes do HTTP: a consulta em voo, a {@code ATTEMPT#0001} com {@code startedAt} e o débito da quota. */
    Reservation reserveFirst(QueryDraft draft, QuotaReservation quota, Instant now) {
        Transactions.Result result = transactions.write(List.of(
                putQuery(draft, QueryState.IN_FLIGHT, now),
                putAttempt(draft.queryId(), 1, now),
                reserveQuota(quota)));
        if (result.committed()) {
            return Reservation.RESERVED;
        }
        return result.conditionFailedAt(0) ? Reservation.QUERY_EXISTS : Reservation.QUOTA_EXHAUSTED;
    }

    /** Retry do worker: nova tentativa e nova reserva de quota, só com a consulta ainda em voo. */
    Reservation reserveNext(String queryId, int sequence, QuotaReservation quota, Instant now) {
        Transactions.Result result = transactions.write(List.of(
                TransactWriteItem.builder()
                        .conditionCheck(check -> check
                                .tableName(properties.tableName())
                                .key(Items.key(Items.queryPk(queryId), META))
                                .conditionExpression(STATE + " = :inFlight")
                                .expressionAttributeNames(STATE_NAME)
                                .expressionAttributeValues(Map.of(":inFlight", s(QueryState.IN_FLIGHT.name()))))
                        .build(),
                putAttempt(queryId, sequence, now),
                reserveQuota(quota)));
        if (result.committed()) {
            return Reservation.RESERVED;
        }
        if (result.conditionFailedAt(2)) {
            return Reservation.QUOTA_EXHAUSTED;
        }
        return Reservation.NOT_IN_FLIGHT;
    }

    /** Encerra uma tentativa sem concluir a consulta, como depois de um erro transitório. */
    void finishAttempt(String queryId, Settlement.AttemptResult attempt, Instant now) {
        AttemptUpdate update = attemptUpdate(attempt, now);
        dynamoDb.updateItem(builder -> builder
                .tableName(properties.tableName())
                .key(Items.key(Items.queryPk(queryId), Items.attemptSk(attempt.sequence())))
                .updateExpression(update.expression())
                .conditionExpression(AttemptUpdate.CONDITION)
                .expressionAttributeValues(update.values()));
    }

    /**
     * Grava a conclusão numa transação. Devolve {@code false} quando a consulta já não estava em voo (ou já
     * existia, na conclusão sem chamada): outra entrega concluiu antes e nada foi gravado.
     */
    boolean settle(Settlement settlement, Instant now) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(settlement.newQuery() == null
                ? settleQuery(settlement.queryId(), settlement.finalState(), now)
                : putQuery(settlement.newQuery(), settlement.finalState(), now));
        if (settlement.attempt() != null) {
            items.add(finishAttemptItem(settlement.queryId(), settlement.attempt(), now));
        }
        if (settlement.evidence() != null) {
            items.add(putEvidence(settlement.queryId(), settlement.evidence()));
        }
        if (settlement.event() != null) {
            items.add(outbox.put(settlement.event(), now));
            items.add(putConsumed(settlement.consumedEventId(), settlement.workRef(), now));
        }
        return transactions.write(items).committed();
    }

    /** Resposta {@code 202}: grava o {@code providerRequestId} na consulta e na tentativa (§8, passo 3). */
    boolean recordAccepted(String queryId, Settlement.AttemptResult attempt, Instant now) {
        TransactWriteItem query = TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(queryId), META))
                        .updateExpression("SET providerRequestId = :requestId, updatedAt = :now")
                        .conditionExpression(STATE + " = :inFlight")
                        .expressionAttributeNames(STATE_NAME)
                        .expressionAttributeValues(Map.of(
                                ":requestId", s(attempt.providerRequestId()),
                                ":now", s(Timestamps.format(now)),
                                ":inFlight", s(QueryState.IN_FLIGHT.name()))))
                .build();
        return transactions.write(List.of(query, finishAttemptItem(queryId, attempt, now))).committed();
    }

    private TransactWriteItem putQuery(QueryDraft draft, QueryState state, Instant now) {
        Map<String, AttributeValue> item = Items.item(Items.queryPk(draft.queryId()), META, Items.TYPE_QUERY);
        item.put("queryId", s(draft.queryId()));
        item.put("operation", s(draft.operation()));
        item.put("provider", s(draft.provider()));
        putIfPresent(item, "providerOperation", draft.providerOperation());
        putIfPresent(item, "contractVersion", draft.contractVersion());
        item.put("organizationId", s(draft.organizationId()));
        item.put("state", s(state.name()));
        item.put("clientReference", s(draft.queryId()));
        item.put("workRef", s(draft.workRef()));
        item.put("quoteId", s(draft.quoteId()));
        item.put("version", n(draft.version()));
        putIfPresent(item, "insurerId", draft.insurerId());
        putIfPresent(item, "queryKey", draft.queryKey());
        item.put("deadlineAt", s(Timestamps.format(draft.deadlineAt())));
        item.put("createdAt", s(Timestamps.format(now)));
        item.put("updatedAt", s(Timestamps.format(now)));
        if (state == QueryState.IN_FLIGHT) {
            item.put(GSI2PK, s(Items.QUERY_IN_FLIGHT));
            item.put(GSI2SK, s(Timestamps.format(draft.deadlineAt())));
        } else if (state == QueryState.UNKNOWN) {
            item.put(GSI2PK, s(Items.QUERY_UNKNOWN));
            item.put(GSI2SK, s(Timestamps.format(now)));
        }
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
    }

    private TransactWriteItem settleQuery(String queryId, QueryState state, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":state", s(state.name()));
        values.put(":now", s(Timestamps.format(now)));
        values.put(":inFlight", s(QueryState.IN_FLIGHT.name()));
        String expression;
        if (state == QueryState.UNKNOWN) {
            values.put(":unknown", s(Items.QUERY_UNKNOWN));
            expression = STATE + " = :state, updatedAt = :now, gsi2pk = :unknown, gsi2sk = :now";
        } else {
            expression = STATE + " = :state, updatedAt = :now REMOVE gsi2pk, gsi2sk";
        }
        String updateExpression = "SET " + expression;
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(queryId), META))
                        .updateExpression(updateExpression)
                        .conditionExpression(STATE + " = :inFlight")
                        .expressionAttributeNames(STATE_NAME)
                        .expressionAttributeValues(values))
                .build();
    }

    private TransactWriteItem putAttempt(String queryId, int sequence, Instant now) {
        Map<String, AttributeValue> item = Items.item(Items.queryPk(queryId), Items.attemptSk(sequence),
                Items.TYPE_ATTEMPT);
        item.put("queryId", s(queryId));
        item.put("sequence", n(sequence));
        item.put("startedAt", s(Timestamps.format(now)));
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
    }

    private TransactWriteItem finishAttemptItem(String queryId, Settlement.AttemptResult attempt, Instant now) {
        AttemptUpdate update = attemptUpdate(attempt, now);
        return TransactWriteItem.builder()
                .update(builder -> builder
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(queryId), Items.attemptSk(attempt.sequence())))
                        .updateExpression(update.expression())
                        .conditionExpression(AttemptUpdate.CONDITION)
                        .expressionAttributeValues(update.values()))
                .build();
    }

    private static AttemptUpdate attemptUpdate(Settlement.AttemptResult attempt, Instant now) {
        StringBuilder expression = new StringBuilder("SET finishedAt = :now, outcome = :outcome");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":now", s(Timestamps.format(now)));
        values.put(":outcome", s(attempt.outcome()));
        if (attempt.httpStatus() != null) {
            expression.append(", httpStatus = :status");
            values.put(":status", n(attempt.httpStatus()));
        }
        if (attempt.errorClass() != null) {
            expression.append(", errorClass = :errorClass");
            values.put(":errorClass", s(attempt.errorClass()));
        }
        if (attempt.providerRequestId() != null) {
            expression.append(", providerRequestId = :requestId");
            values.put(":requestId", s(attempt.providerRequestId()));
        }
        return new AttemptUpdate(expression.toString(), values);
    }

    /** Uma tentativa é encerrada uma vez só; {@code startedAt} e {@code finishedAt} nunca são reescritos. */
    private record AttemptUpdate(String expression, Map<String, AttributeValue> values) {

        static final String CONDITION = "attribute_exists(pk) AND attribute_not_exists(finishedAt)";
    }

    private TransactWriteItem reserveQuota(QuotaReservation quota) {
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(quota.key(), META))
                        .updateExpression("ADD #used :one SET #type = :quota, #ttl = :ttl")
                        .conditionExpression("attribute_not_exists(#used) OR #used < :limit")
                        .expressionAttributeNames(Map.of("#used", "used", "#type", Items.TYPE, "#ttl", Items.TTL))
                        .expressionAttributeValues(Map.of(
                                ":one", n(1),
                                ":quota", s(Items.TYPE_QUOTA),
                                ":ttl", n(quota.ttlEpochSeconds()),
                                ":limit", n(quota.limit()))))
                .build();
    }

    private TransactWriteItem putEvidence(String queryId, EvidenceDraft evidence) {
        Map<String, AttributeValue> item = Items.item(Items.queryPk(queryId), Items.evidenceSk(evidence.evidenceId()),
                Items.TYPE_EVIDENCE);
        item.put("evidenceId", s(evidence.evidenceId()));
        item.put("queryId", s(queryId));
        item.put("resultType", s(evidence.resultType()));
        putIfPresent(item, "normalizedResult", evidence.normalizedResult());
        putIfPresent(item, "rawResponseRef", evidence.rawResponseRef());
        putIfPresent(item, "observedAt", evidence.observedAt());
        item.put("receivedAt", s(evidence.receivedAt()));
        putIfPresent(item, "reusableUntil", evidence.reusableUntil());
        putIfPresent(item, "checksum", evidence.checksum());
        putIfPresent(item, "errorClass", evidence.errorClass());
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
    }

    private TransactWriteItem putConsumed(String eventId, String workRef, Instant now) {
        Map<String, AttributeValue> item = Items.item(Items.consumedPk(eventId), META, Items.TYPE_CONSUMED);
        item.put("consumedAt", s(Timestamps.format(now)));
        putIfPresent(item, "workRef", workRef);
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
    }
}

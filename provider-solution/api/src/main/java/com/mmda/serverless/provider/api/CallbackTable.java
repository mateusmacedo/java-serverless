package com.mmda.serverless.provider.api;

import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.Correlation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * {@code INBOX#}, {@code CORR#} e a conclusão de consultas por callback na tabela da provider (§5, §8). Os itens
 * de consulta, tentativa, evidência, outbox e {@code CONSUMED} têm o formato que os workers gravam.
 */
@Component
class CallbackTable {

    static final String RECEIVED = "RECEIVED";
    static final String PROCESSED = "PROCESSED";
    static final String UNMATCHED = "UNMATCHED";
    private static final String META = "META";
    private static final String OUTBOX_PENDING = "OUTBOX_PENDING";
    private static final String INBOX_RECEIVED = "INBOX_RECEIVED";
    private static final String NOT_EXISTS = "attribute_not_exists(pk)";

    private final DynamoDbClient dynamoDb;
    private final Transactions transactions;
    private final ApiProperties properties;

    CallbackTable(DynamoDbClient dynamoDb, Transactions transactions, ApiProperties properties) {
        this.dynamoDb = dynamoDb;
        this.transactions = transactions;
        this.properties = properties;
    }

    enum Processing {
        SETTLED,
        /** A consulta já estava concluída: só a inbox passou a {@code PROCESSED}, sem outbox. */
        INBOX_ONLY,
        ALREADY_PROCESSED
    }

    record InboxEntry(String externalEventId, String payload, String providerRequestId, String receivedAt,
                      String processingStatus) {

        /**
         * Ainda sem resultado gravado. WHY: o reconciler marca {@code UNMATCHED} o callback sem correlação, e a
         * repetição dele refaz o encontro em vez de responder como já processado (M4).
         */
        boolean received() {
            return RECEIVED.equals(processingStatus) || UNMATCHED.equals(processingStatus);
        }
    }

    record Attempt(int sequence, String finishedAt, String providerRequestId) {
    }

    record QueryView(String queryId, QueryState state, String insurerId, String providerRequestId, String workRef,
                     String quoteId, int version, String requestEventId, List<Attempt> attempts) {

        /**
         * Tentativa que o callback conclui: a do {@code 202} com o mesmo {@code providerRequestId}; sem ela, como
         * no callback que chega antes da gravação do {@code 202}, a última aberta.
         */
        Optional<Attempt> callbackAttempt(String callbackProviderRequestId) {
            return attempts.stream()
                    .filter(attempt -> attempt.providerRequestId() != null
                            && attempt.providerRequestId().equals(callbackProviderRequestId))
                    .findFirst()
                    .or(() -> attempts.stream().filter(attempt -> attempt.finishedAt() == null)
                            .reduce((first, last) -> last))
                    .or(() -> attempts.stream().reduce((first, last) -> last));
        }

        boolean settleable() {
            return state == QueryState.IN_FLIGHT || state == QueryState.UNKNOWN;
        }
    }

    /** Resultado de um callback para a consulta; {@code attemptSequence} nulo quando a tentativa já estava encerrada. */
    record CallbackSettlement(String provider, String externalEventId, String queryId, QueryState finalState,
                              Integer attemptSequence, String attemptOutcome, String attemptErrorClass,
                              String providerRequestId,
                              Map<String, AttributeValue> evidence, String eventId, String detailType, String detail,
                              String consumedEventId, String workRef) {
    }

    /** Grava o callback recebido; {@code false} quando o {@code externalEventId} já estava na inbox. */
    boolean putInbox(String provider, CallbackEnvelope callback, String payload, Instant now) {
        String receivedAt = Timestamps.format(now);
        Map<String, AttributeValue> item = item(inboxPk(provider, callback.externalEventId()), "INBOX");
        item.put("provider", s(provider));
        item.put("externalEventId", s(callback.externalEventId()));
        putIfPresent(item, "providerRequestId", callback.providerRequestId());
        putIfPresent(item, "clientReference", callback.clientReference());
        item.put("payload", s(payload));
        item.put("receivedAt", s(receivedAt));
        item.put("processingStatus", s(RECEIVED));
        item.put("gsi2pk", s(INBOX_RECEIVED));
        item.put("gsi2sk", s(receivedAt));
        try {
            dynamoDb.putItem(builder -> builder
                    .tableName(properties.tableName())
                    .item(item)
                    .conditionExpression(NOT_EXISTS));
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    Optional<InboxEntry> loadInbox(String provider, String externalEventId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .key(key(inboxPk(provider, externalEventId), META)))
                .item();
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new InboxEntry(externalEventId, string(item, "payload"), string(item, "providerRequestId"),
                string(item, "receivedAt"), string(item, "processingStatus")));
    }

    /** Metade da API: {@code ADD pendingEvents}, criando o item se faltar, com {@code ALL_NEW}. */
    Correlation addPending(String provider, String providerRequestId, String externalEventId, Instant now) {
        Map<String, AttributeValue> image = dynamoDb.updateItem(builder -> builder
                        .tableName(properties.tableName())
                        .key(key("CORR#" + provider + "#" + providerRequestId, META))
                        .updateExpression("ADD pendingEvents :event SET #type = :type, #provider = :provider, "
                                + "providerRequestId = :requestId, updatedAt = :now")
                        .expressionAttributeNames(Map.of("#type", "type", "#provider", "provider"))
                        .expressionAttributeValues(Map.of(
                                ":event", AttributeValue.fromSs(List.of(externalEventId)),
                                ":type", s("CORRELATION"),
                                ":provider", s(provider),
                                ":requestId", s(providerRequestId),
                                ":now", s(Timestamps.format(now))))
                        .returnValues(ReturnValue.ALL_NEW))
                .attributes();
        AttributeValue pending = image.get("pendingEvents");
        return new Correlation(string(image, "queryId"), pending == null ? null : Set.copyOf(pending.ss()));
    }

    Optional<QueryView> loadQuery(String queryId) {
        List<Map<String, AttributeValue>> items = dynamoDb.query(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", s("QUERY#" + queryId))))
                .items();
        Map<String, AttributeValue> meta = null;
        List<Attempt> attempts = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) {
            String sk = string(item, "sk");
            if (META.equals(sk)) {
                meta = item;
            } else if (sk.startsWith("ATTEMPT#")) {
                attempts.add(new Attempt(Integer.parseInt(item.get("sequence").n()), string(item, "finishedAt"),
                        string(item, "providerRequestId")));
            }
        }
        if (meta == null) {
            return Optional.empty();
        }
        attempts.sort(Comparator.comparingInt(Attempt::sequence));
        AttributeValue version = meta.get("version");
        return Optional.of(new QueryView(queryId, QueryState.valueOf(string(meta, "state")), string(meta, "insurerId"),
                string(meta, "providerRequestId"), string(meta, "workRef"), string(meta, "quoteId"),
                version == null ? 0 : Integer.parseInt(version.n()), string(meta, "requestEventId"),
                List.copyOf(attempts)));
    }

    /**
     * Uma transação passa a inbox de {@code RECEIVED} para {@code PROCESSED} e grava a consulta concluída, a
     * evidência, o outbox, o {@code CONSUMED} do pedido e, se aberta, a tentativa (§8). Com a consulta já
     * concluída, só a inbox muda. Se só o outbox, o {@code CONSUMED} ou a tentativa já existiam, a conclusão é
     * refeita sem eles, como na conclusão dos workers.
     */
    Processing settle(CallbackSettlement settlement, Instant now) {
        List<TransactWriteItem> items = new ArrayList<>(List.of(
                processed(settlement.provider(), settlement.externalEventId(), settlement.queryId(), now),
                settleQuery(settlement.queryId(), settlement.finalState(), now),
                put(settlement.evidence()),
                outbox(settlement, now),
                consumed(settlement.consumedEventId(), settlement.workRef(), now)));
        if (settlement.attemptSequence() != null) {
            items.add(finishAttempt(settlement, now));
        }
        for (int round = 0; round < 2; round++) {
            Transactions.Result result = transactions.write(items);
            if (result.committed()) {
                return Processing.SETTLED;
            }
            if (result.conditionFailedAt(0)) {
                return Processing.ALREADY_PROCESSED;
            }
            if (result.conditionFailedAt(1) || result.conditionFailedAt(2)) {
                return markProcessed(settlement.provider(), settlement.externalEventId(), settlement.queryId(), now)
                        ? Processing.INBOX_ONLY
                        : Processing.ALREADY_PROCESSED;
            }
            List<TransactWriteItem> retry = new ArrayList<>(items.subList(0, 3));
            for (int index = 3; index < items.size(); index++) {
                if (!result.conditionFailedAt(index)) {
                    retry.add(items.get(index));
                }
            }
            items = retry;
        }
        throw new IllegalStateException("The callback " + settlement.externalEventId() + " could not be settled");
    }

    /** Só a inbox, para ela não ficar parada em {@code RECEIVED} quando a consulta já estava concluída. */
    boolean markProcessed(String provider, String externalEventId, String queryId, Instant now) {
        return transactions.write(List.of(processed(provider, externalEventId, queryId, now))).committed();
    }

    /** Evidência imutável da consulta, como a dos workers ({@code EVIDENCE#<evidenceId>}). */
    static Map<String, AttributeValue> evidence(String queryId, String evidenceId, String resultType,
                                                String normalizedResult, String rawResponseRef, String receivedAt,
                                                String checksum, String errorClass) {
        Map<String, AttributeValue> item = item("QUERY#" + queryId, "EVIDENCE#" + evidenceId, "EVIDENCE");
        item.put("evidenceId", s(evidenceId));
        item.put("queryId", s(queryId));
        item.put("resultType", s(resultType));
        item.put("normalizedResult", s(normalizedResult));
        putIfPresent(item, "rawResponseRef", rawResponseRef);
        item.put("observedAt", s(receivedAt));
        item.put("receivedAt", s(receivedAt));
        putIfPresent(item, "checksum", checksum);
        putIfPresent(item, "errorClass", errorClass);
        return item;
    }

    private TransactWriteItem processed(String provider, String externalEventId, String queryId, Instant now) {
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(key(inboxPk(provider, externalEventId), META))
                        .updateExpression("SET processingStatus = :processed, processedAt = :now, queryId = :queryId "
                                + "REMOVE gsi2pk, gsi2sk")
                        .conditionExpression("processingStatus IN (:received, :unmatched)")
                        .expressionAttributeValues(Map.of(
                                ":processed", s(PROCESSED),
                                ":received", s(RECEIVED),
                                ":unmatched", s(UNMATCHED),
                                ":now", s(Timestamps.format(now)),
                                ":queryId", s(queryId))))
                .build();
    }

    /**
     * WHY: além de {@code IN_FLIGHT}, aceita {@code UNKNOWN}: o callback é a resposta que faltava a uma chamada
     * sem resposta depois do envio, e concluir por ele evita a reconciliação.
     */
    private TransactWriteItem settleQuery(String queryId, QueryState state, Instant now) {
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(key("QUERY#" + queryId, META))
                        .updateExpression("SET #state = :state, updatedAt = :now REMOVE gsi2pk, gsi2sk")
                        .conditionExpression("#state IN (:inFlight, :unknown)")
                        .expressionAttributeNames(Map.of("#state", "state"))
                        .expressionAttributeValues(Map.of(
                                ":state", s(state.name()),
                                ":now", s(Timestamps.format(now)),
                                ":inFlight", s(QueryState.IN_FLIGHT.name()),
                                ":unknown", s(QueryState.UNKNOWN.name()))))
                .build();
    }

    private TransactWriteItem finishAttempt(CallbackSettlement settlement, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":now", s(Timestamps.format(now)));
        values.put(":outcome", s(settlement.attemptOutcome()));
        String expression = "SET finishedAt = :now, outcome = :outcome";
        if (settlement.attemptErrorClass() != null) {
            values.put(":errorClass", s(settlement.attemptErrorClass()));
            expression += ", errorClass = :errorClass";
        }
        if (settlement.providerRequestId() != null) {
            values.put(":requestId", s(settlement.providerRequestId()));
            expression += ", providerRequestId = :requestId";
        }
        String update = expression;
        return TransactWriteItem.builder()
                .update(builder -> builder
                        .tableName(properties.tableName())
                        .key(key("QUERY#" + settlement.queryId(), "ATTEMPT#"
                                + Identities.sequence(settlement.attemptSequence())))
                        .updateExpression(update)
                        .conditionExpression("attribute_exists(pk) AND attribute_not_exists(finishedAt)")
                        .expressionAttributeValues(values))
                .build();
    }

    private TransactWriteItem outbox(CallbackSettlement settlement, Instant now) {
        String createdAt = Timestamps.format(now);
        Map<String, AttributeValue> item = item("OUTBOX#" + settlement.eventId(), META, "OUTBOX");
        item.put("eventId", s(settlement.eventId()));
        item.put("detailType", s(settlement.detailType()));
        item.put("detail", s(settlement.detail()));
        item.put("createdAt", s(createdAt));
        item.put("attempts", AttributeValue.fromN("0"));
        item.put("gsi2pk", s(OUTBOX_PENDING));
        item.put("gsi2sk", s(createdAt));
        return put(item);
    }

    private TransactWriteItem consumed(String eventId, String workRef, Instant now) {
        Map<String, AttributeValue> item = item("CONSUMED#" + eventId, META, "CONSUMED");
        item.put("consumedAt", s(Timestamps.format(now)));
        putIfPresent(item, "workRef", workRef);
        return put(item);
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression(NOT_EXISTS))
                .build();
    }

    private static String inboxPk(String provider, String externalEventId) {
        return "INBOX#" + provider + "#" + externalEventId;
    }

    private static Map<String, AttributeValue> item(String pk, String type) {
        return item(pk, META, type);
    }

    private static Map<String, AttributeValue> item(String pk, String sk, String type) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(pk));
        item.put("sk", s(sk));
        item.put("type", s(type));
        return item;
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("pk", s(pk), "sk", s(sk));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }
}

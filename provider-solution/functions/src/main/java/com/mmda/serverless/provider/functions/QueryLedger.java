package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.GSI2;
import static com.mmda.serverless.provider.functions.Items.GSI2PK;
import static com.mmda.serverless.provider.functions.Items.GSI2SK;
import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.n;
import static com.mmda.serverless.provider.functions.Items.putIfPresent;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.enrichment.ReuseIndexEntry;
import com.mmda.serverless.provider.domain.enrichment.ReusePlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Consultas, tentativas, evidências, quota e {@code CONSUMED} na tabela da provider. Toda gravação que
 * precisa ser atômica com o resultado passa por uma só transação (desenho, §8).
 */
@Component
class QueryLedger {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryLedger.class);
    private static final String STATE = "#state";
    private static final Map<String, String> STATE_NAME = Map.of(STATE, "state");
    private static final Set<QueryState> IN_FLIGHT_ONLY = Set.of(QueryState.IN_FLIGHT);

    private final DynamoDbClient dynamoDb;
    private final Transactions transactions;
    private final OutboxStore outbox;
    private final ProviderProperties properties;
    private final ReuseLedger reuse;

    QueryLedger(DynamoDbClient dynamoDb, Transactions transactions, OutboxStore outbox, ProviderProperties properties,
                ReuseLedger reuse) {
        this.dynamoDb = dynamoDb;
        this.transactions = transactions;
        this.outbox = outbox;
        this.properties = properties;
        this.reuse = reuse;
    }

    enum Reservation {
        RESERVED,
        /** A consulta já existia: outra entrega do mesmo pedido chegou antes. */
        QUERY_EXISTS,
        /** A consulta não está mais em voo. */
        NOT_IN_FLIGHT,
        QUOTA_EXHAUSTED
    }

    enum Leading {
        LEADING,
        /** Outro pedido assumiu ou concluiu a liderança depois da leitura. */
        LEADERSHIP_CHANGED,
        /** Outro líder trocou a evidência do índice depois da leitura. */
        REUSE_CHANGED,
        /** Outra entrega do mesmo pedido criou a consulta antes. */
        QUERY_EXISTS,
        QUOTA_EXHAUSTED
    }

    enum LeaderSettlement {
        SETTLED,
        /**
         * Outra entrega do mesmo pedido já o tinha respondido ({@code HIT} ou {@code JOIN}): a conclusão foi
         * gravada sem o outbox e o {@code CONSUMED} próprios.
         */
        SETTLED_ALREADY_ANSWERED,
        /** A lease foi transferida: a {@code fencingVersion} do {@code INFLIGHT#} não é mais a do líder. */
        FENCED,
        REUSE_CHANGED,
        ALREADY_SETTLED
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
                        Items.string(item, "outcome").orElse(null),
                        Items.string(item, "providerRequestId").orElse(null)));
            }
        }
        if (meta == null) {
            return Optional.empty();
        }
        attempts.sort(Comparator.comparingInt(QueryRecord.Attempt::sequence));
        return Optional.of(new QueryRecord(queryId,
                QueryState.valueOf(Items.string(meta, "state").orElseThrow()),
                Items.string(meta, "providerRequestId").orElse(null),
                Items.string(meta, "queryKey").orElse(null),
                Items.number(meta, "fencingVersion").orElse(null),
                attempts,
                Items.string(meta, "provider").orElse(null),
                Items.string(meta, "workRef").orElse(null),
                Items.string(meta, "quoteId").orElse(null),
                Items.number(meta, "version").map(Long::intValue).orElse(null),
                Items.string(meta, "insurerId").orElse(null),
                Items.string(meta, "requestEventId").orElse(null),
                Items.string(meta, "operation").orElse(null),
                Items.string(meta, "organizationId").orElse(null),
                Items.string(meta, "deadlineAt").map(Timestamps::parse).orElse(null)));
    }

    Optional<String> organizationOf(String queryId) {
        return Items.string(dynamoDb.getItem(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .key(Items.key(Items.queryPk(queryId), META)))
                .item(), "organizationId");
    }

    /** Consultas do índice ({@code QUERY_IN_FLIGHT} ou {@code QUERY_UNKNOWN}) com {@code gsi2sk} antes do instante. */
    List<String> indexedQueries(String gsi2pk, Instant before, int limit) {
        return dynamoDb.query(builder -> builder
                        .tableName(properties.tableName())
                        .indexName(GSI2)
                        .keyConditionExpression("gsi2pk = :state AND gsi2sk < :before")
                        .expressionAttributeValues(Map.of(
                                ":state", s(gsi2pk),
                                ":before", s(Timestamps.format(before))))
                        .limit(limit))
                .items().stream()
                .map(item -> Items.string(item, Items.PK).orElseThrow().substring("QUERY#".length()))
                .toList();
    }

    /**
     * Tira a consulta {@code UNKNOWN} do índice: sem API de situação, nada a conclui sozinho, e a ocorrência
     * {@code UNKNOWN_QUERY} já aberta a acompanha. WHY: no índice, ela ocuparia o lote do reconciler para sempre.
     */
    void leaveUnknownIndex(String queryId) {
        unknownIndex(queryId, "REMOVE gsi2pk, gsi2sk", Map.of());
    }

    /** Leva a consulta {@code UNKNOWN} que continua sem conclusão para o fim do índice, atrás das não conferidas. */
    void deferUnknown(String queryId, Instant now) {
        unknownIndex(queryId, "SET gsi2sk = :now", Map.of(":now", s(Timestamps.format(now))));
    }

    private void unknownIndex(String queryId, String updateExpression, Map<String, AttributeValue> extraValues) {
        Map<String, AttributeValue> values = new HashMap<>(extraValues);
        values.put(":unknown", s(QueryState.UNKNOWN.name()));
        values.put(":index", s(Items.QUERY_UNKNOWN));
        try {
            dynamoDb.updateItem(builder -> builder
                    .tableName(properties.tableName())
                    .key(Items.key(Items.queryPk(queryId), META))
                    .updateExpression(updateExpression)
                    .conditionExpression(STATE + " = :unknown AND gsi2pk = :index")
                    .expressionAttributeNames(STATE_NAME)
                    .expressionAttributeValues(values));
        } catch (ConditionalCheckFailedException changed) {
            LOGGER.debug("Query left the UNKNOWN index meanwhile: queryId={}", queryId);
        }
    }

    boolean isConsumed(String eventId) {
        return dynamoDb.getItem(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .key(Items.key(Items.consumedPk(eventId), META)))
                .hasItem();
    }

    Optional<StoredEvidence> loadEvidence(String queryId, String evidenceId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .key(Items.key(Items.queryPk(queryId), Items.evidenceSk(evidenceId))))
                .item();
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new StoredEvidence(queryId, evidenceId,
                Items.string(item, "resultType").orElseThrow(),
                Items.string(item, "normalizedResult").orElse(null),
                Items.string(item, "reusableUntil").orElse(null),
                Items.string(item, "errorClass").orElse(null),
                Items.string(item, "policyVersion").orElse(null)));
    }

    /**
     * Antes do HTTP, numa transação (§8): a liderança, a conferência do índice lido, a consulta do líder em voo
     * com a {@code fencingVersion}, a {@code ATTEMPT#0001} com {@code startedAt} e o débito da quota.
     */
    Leading lead(QueryDraft draft, ReusePlan.Lead plan, QuotaReservation quota, Instant now) {
        Transactions.Result result = transactions.write(List.of(
                reuse.lead(draft.queryKey(), plan, draft.workRef(), draft.queryId(), now),
                reuse.reuseUnchanged(draft.queryKey(), plan.expectedReuseRevision()),
                putQuery(draft.ledBy(plan.fencingVersion()), QueryState.IN_FLIGHT, now),
                putAttempt(draft.queryId(), 1, now),
                reserveQuota(quota)));
        if (result.committed()) {
            return Leading.LEADING;
        }
        if (result.conditionFailedAt(2)) {
            return Leading.QUERY_EXISTS;
        }
        if (result.conditionFailedAt(0)) {
            return Leading.LEADERSHIP_CHANGED;
        }
        return result.conditionFailedAt(1) ? Leading.REUSE_CHANGED : Leading.QUOTA_EXHAUSTED;
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
     * existia, na conclusão sem chamada): outra entrega concluiu antes e nada foi gravado. Se só o outbox ou o
     * {@code CONSUMED} próprios falharem, outra entrega já respondeu ao pedido: a consulta existente é concluída
     * sem eles, e a conclusão sem chamada não cria consulta.
     */
    boolean settle(Settlement settlement, Instant now) {
        return settleFrom(settlement, IN_FLIGHT_ONLY, now);
    }

    /**
     * {@link #settle} a partir dos estados dados. WHY: a reconciliação conclui também a consulta {@code UNKNOWN}
     * pela API de situação, como o callback que chega depois (§8).
     */
    boolean settleFrom(Settlement settlement, Set<QueryState> from, Instant now) {
        return settleFrom(settlement, from, List.of(), now);
    }

    /**
     * {@link #settle} só se nenhuma tentativa passou das {@code attempts} lidas. WHY: o reconciler conclui sem
     * chamada a consulta sem tentativa aberta; uma reentrega do worker que reservou outra tentativa depois dessa
     * leitura pode estar chamando o fornecedor, e a conclusão "antes da chamada" seria falsa (§9).
     */
    boolean settleWithoutNewAttempt(Settlement settlement, int attempts, Instant now) {
        TransactWriteItem noNewAttempt = TransactWriteItem.builder()
                .conditionCheck(check -> check
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(settlement.queryId()), Items.attemptSk(attempts + 1)))
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
        return settleFrom(settlement, IN_FLIGHT_ONLY, List.of(noNewAttempt), now);
    }

    private boolean settleFrom(Settlement settlement, Set<QueryState> from, List<TransactWriteItem> guards,
                               Instant now) {
        List<TransactWriteItem> conclusion = new ArrayList<>();
        conclusion.add(settlement.newQuery() == null
                ? settleQuery(settlement.queryId(), settlement.finalState(), from, now)
                : putQuery(settlement.newQuery(), settlement.finalState(), now));
        conclusion.addAll(records(settlement, now));
        conclusion.addAll(guards);
        List<TransactWriteItem> items = new ArrayList<>(conclusion);
        items.addAll(ownAnswer(settlement, now));
        Transactions.Result result = transactions.write(items);
        if (result.committed()) {
            return true;
        }
        if (settlement.newQuery() != null || result.conditionFailedAt(0)
                || !answeredBefore(result, conclusion.size())) {
            return false;
        }
        LOGGER.warn("Request already answered by another delivery; the query is concluded without its own event: "
                + "queryId={}", settlement.queryId());
        return transactions.write(conclusion).committed();
    }

    /**
     * Conclusão do líder (§8), numa transação: a consulta, a liderança como lápide (só com a própria
     * {@code fencingVersion} e consulta), o índice de reuso quando o resultado é reutilizável ({@code reuseEntry}
     * não nulo, condicionado à revisão lida), a tentativa, a evidência, o outbox próprio e o {@code CONSUMED}.
     * WHY: outra entrega do mesmo pedido pode tê-lo respondido depois da conferência do {@code CONSUMED}; a
     * liderança conclui mesmo assim, sem o outbox e o {@code CONSUMED} próprios, para os seguidores e o índice.
     */
    LeaderSettlement settleAsLeader(Settlement settlement, LeaderTerm term, ReuseIndexEntry reuseEntry,
                                    Long expectedRevision, Instant now) {
        if (settlement.newQuery() != null || settlement.evidence() == null || settlement.event() == null) {
            throw new IllegalArgumentException("A leader settles its existing query with evidence and an event");
        }
        List<TransactWriteItem> conclusion = new ArrayList<>();
        conclusion.add(settleQuery(settlement.queryId(), settlement.finalState(), IN_FLIGHT_ONLY, now));
        conclusion.add(reuse.complete(term, settlement.queryId(), settlement.evidence().evidenceId(),
                settlement.evidence().resultType(), now));
        if (reuseEntry != null) {
            conclusion.add(reuse.putReuse(term.queryKey(), reuseEntry, expectedRevision, now));
        }
        conclusion.addAll(records(settlement, now));
        conclusion.add(putFanOut(term, settlement, now));
        List<TransactWriteItem> items = new ArrayList<>(conclusion);
        items.addAll(ownAnswer(settlement, now));
        Transactions.Result result = transactions.write(items);
        if (result.committed()) {
            return LeaderSettlement.SETTLED;
        }
        Optional<LeaderSettlement> lost = leaderConflict(result, reuseEntry != null);
        if (lost.isPresent()) {
            return lost.get();
        }
        if (!answeredBefore(result, conclusion.size())) {
            return LeaderSettlement.ALREADY_SETTLED;
        }
        Transactions.Result withoutOwnAnswer = transactions.write(conclusion);
        if (withoutOwnAnswer.committed()) {
            return LeaderSettlement.SETTLED_ALREADY_ANSWERED;
        }
        return leaderConflict(withoutOwnAnswer, reuseEntry != null).orElse(LeaderSettlement.ALREADY_SETTLED);
    }

    private static Optional<LeaderSettlement> leaderConflict(Transactions.Result result, boolean writesReuse) {
        if (result.conditionFailedAt(0)) {
            return Optional.of(LeaderSettlement.ALREADY_SETTLED);
        }
        if (result.conditionFailedAt(1)) {
            return Optional.of(LeaderSettlement.FENCED);
        }
        if (writesReuse && result.conditionFailedAt(2)) {
            return Optional.of(LeaderSettlement.REUSE_CHANGED);
        }
        return Optional.empty();
    }

    /** O outbox ou o {@code CONSUMED} próprios, gravados a partir de {@code ownAnswerIndex}, já existiam. */
    private static boolean answeredBefore(Transactions.Result result, int ownAnswerIndex) {
        return result.conditionFailedAt(ownAnswerIndex) || result.conditionFailedAt(ownAnswerIndex + 1);
    }

    /**
     * Resolução de um pedido sem consulta própria ({@code HIT} ou {@code JOIN}): o outbox e o {@code CONSUMED}
     * do pedido, numa transação. Devolve {@code false} se o pedido já estava resolvido.
     */
    boolean resolve(Settlement.OutboxEvent event, String requestEventId, String workRef, Instant now) {
        return transactions.write(List.of(outbox.put(event, now), putConsumed(requestEventId, workRef, now)))
                .committed();
    }

    private List<TransactWriteItem> records(Settlement settlement, Instant now) {
        List<TransactWriteItem> items = new ArrayList<>();
        if (settlement.attempt() != null) {
            items.add(finishAttemptItem(settlement.queryId(), settlement.attempt(), now));
        }
        if (settlement.evidence() != null) {
            items.add(putEvidence(settlement.queryId(), settlement.evidence()));
        }
        return items;
    }

    private List<TransactWriteItem> ownAnswer(Settlement settlement, Instant now) {
        if (settlement.event() == null) {
            return List.of();
        }
        return List.of(outbox.put(settlement.event(), now),
                putConsumed(settlement.consumedEventId(), settlement.workRef(), now));
    }

    /**
     * Resposta {@code 202}: grava o {@code providerRequestId} na consulta e na tentativa (§8, passo 3). A
     * tentativa continua aberta: o resultado, que chega pelo callback, é que a conclui. Devolve {@code false}
     * quando a consulta já não estava em voo, como depois de um callback com {@code clientReference} mais
     * rápido que esta gravação.
     */
    boolean recordAccepted(String queryId, int sequence, String providerRequestId, Integer httpStatus, Instant now) {
        String acceptedAt = Timestamps.format(now);
        TransactWriteItem query = TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(queryId), META))
                        .updateExpression("SET providerRequestId = :requestId, updatedAt = :now")
                        .conditionExpression(STATE + " = :inFlight")
                        .expressionAttributeNames(STATE_NAME)
                        .expressionAttributeValues(Map.of(
                                ":requestId", s(providerRequestId),
                                ":now", s(acceptedAt),
                                ":inFlight", s(QueryState.IN_FLIGHT.name()))))
                .build();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":requestId", s(providerRequestId));
        values.put(":now", s(acceptedAt));
        String status = "";
        if (httpStatus != null) {
            values.put(":status", n(httpStatus));
            status = ", httpStatus = :status";
        }
        String expression = "SET providerRequestId = :requestId, acceptedAt = :now" + status;
        TransactWriteItem attempt = TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(queryId), Items.attemptSk(sequence)))
                        .updateExpression(expression)
                        .conditionExpression(AttemptUpdate.CONDITION)
                        .expressionAttributeValues(values))
                .build();
        return transactions.write(List.of(query, attempt)).committed();
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
        putIfPresent(item, "requestEventId", draft.requestEventId());
        if (draft.fencingVersion() != null) {
            item.put("fencingVersion", n(draft.fencingVersion()));
        }
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

    private TransactWriteItem settleQuery(String queryId, QueryState state, Set<QueryState> from, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":state", s(state.name()));
        values.put(":now", s(Timestamps.format(now)));
        List<String> allowed = new ArrayList<>();
        from.stream().sorted().forEach(source -> {
            values.put(":from" + source.name(), s(source.name()));
            allowed.add(":from" + source.name());
        });
        String condition = allowed.size() == 1
                ? STATE + " = " + allowed.getFirst()
                : STATE + " IN (" + String.join(", ", allowed) + ")";
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
                        .conditionExpression(condition)
                        .expressionAttributeNames(STATE_NAME)
                        .expressionAttributeValues(values))
                .build();
    }

    /**
     * Fan-out a fazer, gravado na conclusão do líder e encerrado depois dele (§8). WHY: se o líder cai entre a
     * conclusão e o fan-out e outra cadeia começa antes da reentrega dele, só este item ainda diz quais seguidores
     * esperam a evidência; o reconciler refaz o fan-out por ele (pendência do M2, §9).
     */
    private TransactWriteItem putFanOut(LeaderTerm term, Settlement settlement, Instant now) {
        String concludedAt = Timestamps.format(now);
        Map<String, AttributeValue> item = Items.item(Items.queryPk(settlement.queryId()), Items.FANOUT,
                Items.TYPE_FANOUT);
        item.put("queryId", s(settlement.queryId()));
        item.put("queryKey", s(term.queryKey()));
        item.put("leaderWorkRef", s(settlement.workRef()));
        item.put("firstFencingVersion", n(term.firstFencingVersion()));
        item.put("fencingVersion", n(term.fencingVersion()));
        item.put("evidenceId", s(settlement.evidence().evidenceId()));
        item.put("concludedAt", s(concludedAt));
        item.put(GSI2PK, s(Items.FANOUT_PENDING));
        item.put(GSI2SK, s(concludedAt));
        return TransactWriteItem.builder()
                .put(put -> put.tableName(properties.tableName()).item(item))
                .build();
    }

    /** O fan-out da conclusão terminou: o item sai do índice do reconciler e fica como registro. */
    void markFannedOut(String queryId, Instant now) {
        try {
            dynamoDb.updateItem(builder -> builder
                    .tableName(properties.tableName())
                    .key(Items.key(Items.queryPk(queryId), Items.FANOUT))
                    .updateExpression("SET fannedOutAt = :now REMOVE gsi2pk, gsi2sk")
                    .conditionExpression("attribute_exists(pk)")
                    .expressionAttributeValues(Map.of(":now", s(Timestamps.format(now)))));
        } catch (ConditionalCheckFailedException exception) {
            LOGGER.debug("No pending fan-out recorded for query {}", queryId);
        }
    }

    /** Fan-outs gravados antes de {@code concludedBefore} e ainda não encerrados, mais antigos primeiro. */
    List<PendingFanOut> pendingFanOuts(Instant concludedBefore, int limit) {
        return dynamoDb.query(builder -> builder
                        .tableName(properties.tableName())
                        .indexName(GSI2)
                        .keyConditionExpression("gsi2pk = :pending AND gsi2sk < :before")
                        .expressionAttributeValues(Map.of(
                                ":pending", s(Items.FANOUT_PENDING),
                                ":before", s(Timestamps.format(concludedBefore))))
                        .limit(limit))
                .items().stream()
                .map(item -> new PendingFanOut(
                        Items.string(item, "queryId").orElseThrow(),
                        new LeaderTerm(Items.string(item, "queryKey").orElseThrow(),
                                Items.number(item, "fencingVersion").orElseThrow(),
                                Items.number(item, "firstFencingVersion").orElseThrow()),
                        Items.string(item, "leaderWorkRef").orElseThrow(),
                        Items.string(item, "evidenceId").orElseThrow(),
                        Timestamps.parse(Items.string(item, "concludedAt").orElseThrow())))
                .toList();
    }

    /** Fan-out gravado na conclusão de um líder e ainda não encerrado. */
    record PendingFanOut(String queryId, LeaderTerm term, String leaderWorkRef, String evidenceId,
                         Instant concludedAt) {
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

    TransactWriteItem finishAttemptItem(String queryId, Settlement.AttemptResult attempt, Instant now) {
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

    TransactWriteItem putEvidence(String queryId, EvidenceDraft evidence) {
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
        putIfPresent(item, "policyVersion", evidence.policyVersion());
        if (evidence.fencingVersion() != null) {
            item.put("fencingVersion", n(evidence.fencingVersion()));
        }
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
    }

    TransactWriteItem putConsumed(String eventId, String workRef, Instant now) {
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

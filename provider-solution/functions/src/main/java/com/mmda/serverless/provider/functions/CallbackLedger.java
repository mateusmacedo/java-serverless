package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.Correlation;
import java.time.Instant;
import java.util.ArrayList;
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
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * {@code INBOX#} e {@code CORR#} do lado do worker (§5, §8): a metade do
 * encontro depois do {@code 202} e a
 * conclusão das pendências que a API de callbacks guardou antes dela. O formato
 * dos itens é o mesmo que o
 * {@code provider-api} grava.
 */
@Component
class CallbackLedger {

    static final String RECEIVED = "RECEIVED";
    static final String PROCESSED = "PROCESSED";
    static final String UNMATCHED = "UNMATCHED";

    private static final Logger LOGGER = LoggerFactory.getLogger(CallbackLedger.class);
    private static final String SETTLEABLE = "#state IN (:inFlight, :unknown)";

    private final DynamoDbClient dynamoDb;
    private final Transactions transactions;
    private final QueryLedger ledger;
    private final OutboxStore outbox;
    private final ProviderProperties properties;

    CallbackLedger(DynamoDbClient dynamoDb, Transactions transactions, QueryLedger ledger, OutboxStore outbox,
            ProviderProperties properties) {
        this.dynamoDb = dynamoDb;
        this.transactions = transactions;
        this.ledger = ledger;
        this.outbox = outbox;
        this.properties = properties;
    }

    enum Processing {
        SETTLED,
        /**
         * A consulta já estava concluída: só a inbox passou a {@code PROCESSED}, sem
         * outbox.
         */
        INBOX_ONLY,
        ALREADY_PROCESSED
    }

    record InboxEntry(String externalEventId, String payload, String providerRequestId, String receivedAt,
            String processingStatus, String provider, String clientReference) {

        /**
         * Ainda sem resultado gravado. WHY: o {@code UNMATCHED} continua processável,
         * porque a correlação pode
         * aparecer depois, como a metade do worker no {@code CORR#} gravada após o
         * prazo (§8).
         */
        boolean received() {
            return RECEIVED.equals(processingStatus) || UNMATCHED.equals(processingStatus);
        }
    }

    /**
     * Resultado de um callback para a consulta; {@code attempt} é nulo quando a
     * tentativa já estava encerrada.
     */
    record CallbackSettlement(String provider, String externalEventId, String queryId, QueryState finalState,
            Settlement.AttemptResult attempt, EvidenceDraft evidence, Settlement.OutboxEvent event,
            String consumedEventId, String workRef) {
    }

    /**
     * Metade do worker: {@code SET queryId} com {@code ALL_NEW}. Vazio quando o
     * {@code providerRequestId} já
     * está ligado a outra consulta, o que só um fornecedor que repete
     * identificadores provoca.
     */
    Optional<Correlation> joinAsWorker(String provider, String providerRequestId, String queryId, Instant now) {
        try {
            Map<String, AttributeValue> image = dynamoDb.updateItem(builder -> builder
                    .tableName(properties.tableName())
                    .key(Items.key(Items.correlationPk(provider, providerRequestId), META))
                    .updateExpression("SET queryId = :queryId, #type = :type, #provider = :provider, "
                            + "providerRequestId = :requestId, updatedAt = :now")
                    .conditionExpression("attribute_not_exists(queryId) OR queryId = :queryId")
                    .expressionAttributeNames(Map.of("#type", Items.TYPE, "#provider", "provider"))
                    .expressionAttributeValues(Map.of(
                            ":queryId", s(queryId),
                            ":type", s(Items.TYPE_CORRELATION),
                            ":provider", s(provider),
                            ":requestId", s(providerRequestId),
                            ":now", s(Timestamps.format(now))))
                    .returnValues(ReturnValue.ALL_NEW))
                    .attributes();
            AttributeValue pending = image.get("pendingEvents");
            return Optional.of(new Correlation(Items.string(image, "queryId").orElse(null),
                    pending == null ? null : Set.copyOf(pending.ss())));
        } catch (ConditionalCheckFailedException exception) {
            return Optional.empty();
        }
    }

    Optional<InboxEntry> loadInbox(String provider, String externalEventId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder
                .tableName(properties.tableName())
                .consistentRead(true)
                .key(Items.key(Items.inboxPk(provider, externalEventId), META)))
                .item();
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(inboxEntry(item, externalEventId));
    }

    /**
     * Callbacks com {@code processingStatus} do índice ({@code INBOX_RECEIVED} ou
     * {@code INBOX_UNMATCHED}), recebidos
     * antes de {@code receivedBefore}, mais antigos primeiro.
     */
    List<InboxEntry> inboxIndexed(String gsi2pk, Instant receivedBefore, int limit) {
        return dynamoDb.query(builder -> builder
                .tableName(properties.tableName())
                .indexName(Items.GSI2)
                .keyConditionExpression("gsi2pk = :status AND gsi2sk < :before")
                .expressionAttributeValues(Map.of(
                        ":status", s(gsi2pk),
                        ":before", s(Timestamps.format(receivedBefore))))
                .limit(limit))
                .items().stream()
                .map(item -> inboxEntry(item, null))
                .toList();
    }

    /**
     * Callback sem correlação depois de {@code unmatchedCallbackAfterSeconds} (§8):
     * sai do índice dos recebidos e
     * entra no {@code INBOX_UNMATCHED}, com o mesmo {@code receivedAt}, para o
     * reconciler e o alarme.
     */
    boolean markUnmatched(String provider, String externalEventId, Instant now) {
        try {
            dynamoDb.updateItem(builder -> builder
                    .tableName(properties.tableName())
                    .key(Items.key(Items.inboxPk(provider, externalEventId), META))
                    .updateExpression("SET processingStatus = :unmatched, unmatchedAt = :now, gsi2pk = :index")
                    .conditionExpression("processingStatus = :received")
                    .expressionAttributeValues(Map.of(
                            ":unmatched", s(UNMATCHED),
                            ":received", s(RECEIVED),
                            ":index", s(Items.INBOX_UNMATCHED),
                            ":now", s(Timestamps.format(now)))));
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    /**
     * Leva o callback que continua {@code UNMATCHED} para o fim do índice; o
     * {@code receivedAt} não muda. WHY: o
     * índice é lido em ordem e com limite; sem isso, os órfãos ocupariam o lote, e
     * o que encontrou a consulta depois
     * não seria processado.
     */
    void deferUnmatched(String provider, String externalEventId, Instant now) {
        try {
            dynamoDb.updateItem(builder -> builder
                    .tableName(properties.tableName())
                    .key(Items.key(Items.inboxPk(provider, externalEventId), META))
                    .updateExpression("SET gsi2sk = :now")
                    .conditionExpression("processingStatus = :unmatched AND gsi2pk = :index")
                    .expressionAttributeValues(Map.of(
                            ":now", s(Timestamps.format(now)),
                            ":unmatched", s(UNMATCHED),
                            ":index", s(Items.INBOX_UNMATCHED))));
        } catch (ConditionalCheckFailedException processed) {
            LOGGER.debug("Callback left the UNMATCHED index meanwhile: externalEventId={}", externalEventId);
        }
    }

    /**
     * {@code queryId} da metade do worker no {@code CORR#}, quando ela já foi
     * gravada.
     */
    Optional<String> correlatedQuery(String provider, String providerRequestId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder
                .tableName(properties.tableName())
                .consistentRead(true)
                .key(Items.key(Items.correlationPk(provider, providerRequestId), META)))
                .item();
        return Items.string(item, "queryId");
    }

    private static InboxEntry inboxEntry(Map<String, AttributeValue> item, String externalEventId) {
        return new InboxEntry(Items.string(item, "externalEventId").orElse(externalEventId),
                Items.string(item, "payload").orElse(null),
                Items.string(item, "providerRequestId").orElse(null),
                Items.string(item, "receivedAt").orElse(null),
                Items.string(item, "processingStatus").orElse(null),
                Items.string(item, "provider").orElse(null),
                Items.string(item, "clientReference").orElse(null));
    }

    /**
     * Uma transação passa a inbox de {@code RECEIVED} para {@code PROCESSED} e
     * grava a consulta concluída, a
     * evidência, o outbox, o {@code CONSUMED} do pedido e, se aberta, a tentativa
     * (§8). Com a consulta já
     * concluída, só a inbox muda. Se só o outbox, o {@code CONSUMED} ou a tentativa
     * já existiam, a conclusão é
     * refeita sem eles, como na conclusão síncrona.
     */
    Processing settle(CallbackSettlement settlement, Instant now) {
        List<TransactWriteItem> items = new ArrayList<>(List.of(
                processed(settlement.provider(), settlement.externalEventId(), settlement.queryId(), now),
                settleQuery(settlement.queryId(), settlement.finalState(), now),
                ledger.putEvidence(settlement.queryId(), settlement.evidence()),
                outbox.put(settlement.event(), now),
                ledger.putConsumed(settlement.consumedEventId(), settlement.workRef(), now)));
        if (settlement.attempt() != null) {
            items.add(ledger.finishAttemptItem(settlement.queryId(), settlement.attempt(), now));
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

    /**
     * Só a inbox, para ela não ficar parada em {@code RECEIVED} quando a consulta
     * já estava concluída.
     */
    boolean markProcessed(String provider, String externalEventId, String queryId, Instant now) {
        return transactions.write(List.of(processed(provider, externalEventId, queryId, now))).committed();
    }

    private TransactWriteItem processed(String provider, String externalEventId, String queryId, Instant now) {
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.inboxPk(provider, externalEventId), META))
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
     * WHY: além de {@code IN_FLIGHT}, aceita {@code UNKNOWN}: o callback é a
     * resposta que faltava a uma chamada
     * sem resposta depois do envio, e concluir por ele evita a reconciliação.
     */
    private TransactWriteItem settleQuery(String queryId, QueryState state, Instant now) {
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.queryPk(queryId), META))
                        .updateExpression("SET #state = :state, updatedAt = :now REMOVE gsi2pk, gsi2sk")
                        .conditionExpression(SETTLEABLE)
                        .expressionAttributeNames(Map.of("#state", "state"))
                        .expressionAttributeValues(Map.of(
                                ":state", s(state.name()),
                                ":now", s(Timestamps.format(now)),
                                ":inFlight", s(QueryState.IN_FLIGHT.name()),
                                ":unknown", s(QueryState.UNKNOWN.name()))))
                .build();
    }
}

package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Retries;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Acesso das functions à tabela da quote-solution: leituras consistentes, transações com repetição de
 * {@code TransactionConflict} e a atualização do resumo do {@code META} (§4 do desenho).
 */
@ApplicationScoped
public class QuoteTable {

    static final String NOT_EXISTS = "attribute_not_exists(" + QuoteKeys.PK + ")";
    private static final Logger LOG = Logger.getLogger(QuoteTable.class);

    private final DynamoDbClient dynamoDb;
    private final QuoteFunctionsConfig config;
    private final ObjectMapper objectMapper;
    private final Retries retries = Retries.transactionConflicts();

    QuoteTable(DynamoDbClient dynamoDb, QuoteFunctionsConfig config, ObjectMapper objectMapper) {
        this.dynamoDb = dynamoDb;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    String name() {
        return config.tableName();
    }

    static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(QuoteKeys.PK, s(pk), QuoteKeys.SK, s(sk));
    }

    Optional<Map<String, AttributeValue>> get(String pk, String sk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(name())
                .key(key(pk, sk))
                .consistentRead(true)
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    /** Itens da partição cujo {@code sk} começa com {@code prefix}, com leitura consistente. */
    List<Map<String, AttributeValue>> query(String pk, String prefix) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryResponse page = dynamoDb.query(prefixQuery(pk, prefix).exclusiveStartKey(startKey).build());
            items.addAll(page.items());
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return items;
    }

    int count(String pk, String prefix) {
        int count = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            QueryResponse page = dynamoDb.query(prefixQuery(pk, prefix).select(Select.COUNT)
                    .exclusiveStartKey(startKey).build());
            count += page.count();
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return count;
    }

    private QueryRequest.Builder prefixQuery(String pk, String prefix) {
        return QueryRequest.builder()
                .tableName(name())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", QuoteKeys.PK, "#sk", QuoteKeys.SK))
                .expressionAttributeValues(Map.of(":pk", s(pk), ":prefix", s(prefix)))
                .consistentRead(true);
    }

    /** Executa a transação; {@code TransactionConflict} é repetido com backoff e jitter (§9 do desenho). */
    void transact(List<TransactWriteItem> writes) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(writes).build();
        retries.run(() -> dynamoDb.transactWriteItems(request), QuoteTable::isTransactionConflict);
    }

    /** Atualização condicional; devolve {@code false} quando a condição falha. */
    boolean updateIf(UpdateItemRequest request) {
        try {
            dynamoDb.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException conditionFailed) {
            return false;
        }
    }

    UpdateItemRequest.Builder update(String pk, String sk) {
        return UpdateItemRequest.builder().tableName(name()).key(key(pk, sk));
    }

    TransactWriteItem put(Map<String, AttributeValue> item, String condition) {
        Put.Builder put = Put.builder().tableName(name()).item(item);
        if (condition != null) {
            put.conditionExpression(condition);
        }
        return TransactWriteItem.builder().put(put.build()).build();
    }

    TransactWriteItem update(String pk, String sk, String expression, String condition, Map<String, String> names,
            Map<String, AttributeValue> values) {
        Update.Builder update = Update.builder().tableName(name()).key(key(pk, sk)).updateExpression(expression);
        if (condition != null) {
            update.conditionExpression(condition);
        }
        if (!names.isEmpty()) {
            update.expressionAttributeNames(names);
        }
        if (!values.isEmpty()) {
            update.expressionAttributeValues(values);
        }
        return TransactWriteItem.builder().update(update.build()).build();
    }

    /** Item de outbox: o {@code INSERT} dele no stream aciona a {@code outbox-relay}. */
    Map<String, AttributeValue> outboxItem(String eventId, String detailType, Map<String, Object> detail,
            String quoteId, int version, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(QuoteKeys.PK, s(QuoteKeys.outboxPk(eventId)));
        item.put(QuoteKeys.SK, s(QuoteKeys.META));
        item.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_OUTBOX));
        item.put("eventId", s(eventId));
        item.put("detailType", s(detailType));
        item.put("detail", s(toJson(detail)));
        item.put("quoteId", s(quoteId));
        item.put("version", n(version));
        item.put("createdAt", s(now.toString()));
        item.put("attempts", n(0));
        item.put(QuoteKeys.GSI2_PK, s(QuoteKeys.OUTBOX_PENDING));
        item.put(QuoteKeys.GSI2_SK, s(now.toString()));
        return item;
    }

    /**
     * Resumo da versão atual no {@code META}, com valores absolutos e condição {@code currentVersion = :v}.
     * WHY: a condição falha quando a versão foi substituída, e o resumo da versão nova não pode ser
     * sobrescrito pela antiga; o {@code reconciler} repara divergências (M4). Roda depois do commit do passo,
     * então uma falha aqui só fica no log: derrubar o passo já gravado faria a máquina tratá-lo como falho.
     */
    void refreshSummary(String quoteId, int version, Instant now) {
        try {
            writeSummary(quoteId, version, now);
        } catch (SdkException failure) {
            LOG.warnf("summary of quoteId=%s version=%d left for the reconciler: %s", quoteId, version,
                    failure.getMessage());
        }
    }

    private void writeSummary(String quoteId, int version, Instant now) {
        Optional<Map<String, AttributeValue>> versionItem = get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version));
        if (versionItem.isEmpty()) {
            return;
        }
        int offerCount = count(QuoteKeys.quotePk(quoteId), QuoteKeys.offerPrefix(version));
        String outcome = Attr.string(versionItem.get(), "outcome");
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":phase", s(Attr.string(versionItem.get(), "phase")),
                ":offerCount", n(offerCount),
                ":now", s(now.toString()),
                ":v", n(version)));
        String expression = "SET phase = :phase, offerCount = :offerCount, updatedAt = :now";
        if (outcome != null) {
            values.put(":outcome", s(outcome));
            expression += ", outcome = :outcome";
        } else {
            expression += " REMOVE outcome";
        }
        updateIf(update(QuoteKeys.quotePk(quoteId), QuoteKeys.META)
                .updateExpression(expression)
                .conditionExpression("currentVersion = :v")
                .expressionAttributeValues(values)
                .build());
    }

    static boolean isTransactionConflict(RuntimeException failure) {
        return reasons(failure).contains("TransactionConflict");
    }

    /**
     * Cancelamento por condição de algum item. WHY: throttling ou {@code TransactionConflict} que sobrou das
     * repetições não dizem nada sobre o estado gravado; quem chama relança e a mensagem volta para nova
     * tentativa, em vez de virar resultado tardio ou repetido.
     */
    static boolean isConditionFailure(RuntimeException failure) {
        return reasons(failure).contains("ConditionalCheckFailed");
    }

    /** Códigos de cancelamento por item, na ordem das ações da transação. */
    static List<String> reasons(RuntimeException failure) {
        if (failure instanceof TransactionCanceledException canceled && canceled.hasCancellationReasons()) {
            return canceled.cancellationReasons().stream().map(CancellationReason::code).toList();
        }
        return List.of();
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

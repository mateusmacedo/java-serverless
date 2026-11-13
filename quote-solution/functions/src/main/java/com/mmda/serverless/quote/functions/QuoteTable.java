package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Retries;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            Optional<Map<String, AttributeValue>> versionItem =
                    get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version));
            if (versionItem.isEmpty()) {
                return;
            }
            writeSummary(quoteId, version, versionItem.get(),
                    count(QuoteKeys.quotePk(quoteId), QuoteKeys.offerPrefix(version)), now);
            if (QuoteKeys.SUMMARY_PENDING.equals(Attr.string(versionItem.get(), QuoteKeys.GSI2_PK))) {
                clearSummaryPending(quoteId, version);
            }
        } catch (SdkException failure) {
            LOG.warnf("summary of quoteId=%s version=%d left for the reconciler: %s", quoteId, version,
                    failure.getMessage());
        }
    }

    /** Tira a versão {@code TERMINAL} do índice {@code SUMMARY_PENDING} depois que o resumo foi conferido. */
    void clearSummaryPending(String quoteId, int version) {
        updateIf(update(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version))
                .updateExpression("REMOVE " + QuoteKeys.GSI2_PK + ", " + QuoteKeys.GSI2_SK)
                .conditionExpression(QuoteKeys.GSI2_PK + " = :pending")
                .expressionAttributeValues(Map.of(":pending", s(QuoteKeys.SUMMARY_PENDING)))
                .build());
    }

    /**
     * Reparo do {@code reconciler} (M4): compara o resumo do {@code META} com o {@code V#currentVersion} e o
     * regrava só quando diverge. Devolve {@code true} quando reparou.
     */
    boolean repairSummary(String quoteId, int version, Instant now) {
        Optional<Map<String, AttributeValue>> meta = get(QuoteKeys.quotePk(quoteId), QuoteKeys.META);
        if (meta.isEmpty() || Attr.number(meta.get(), "currentVersion", 0) != version) {
            return false;
        }
        Optional<Map<String, AttributeValue>> versionItem = get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version));
        if (versionItem.isEmpty()) {
            return false;
        }
        int offerCount = count(QuoteKeys.quotePk(quoteId), QuoteKeys.offerPrefix(version));
        boolean current = Objects.equals(Attr.string(meta.get(), "phase"), Attr.string(versionItem.get(), "phase"))
                && Objects.equals(Attr.string(meta.get(), "outcome"), Attr.string(versionItem.get(), "outcome"))
                && Attr.number(meta.get(), "offerCount", -1) == offerCount;
        return !current && writeSummary(quoteId, version, versionItem.get(), offerCount, now);
    }

    private boolean writeSummary(String quoteId, int version, Map<String, AttributeValue> versionItem, int offerCount,
            Instant now) {
        String outcome = Attr.string(versionItem, "outcome");
        String phase = Attr.string(versionItem, "phase");
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":phase", s(phase),
                ":offerCount", n(offerCount),
                ":now", s(now.toString()),
                ":v", n(version)));
        String condition = "currentVersion = :v";
        if (!Phase.TERMINAL.name().equals(phase)) {
            // INVARIANT: TERMINAL é final; uma leitura anterior à consolidação não rebaixa o resumo
            values.put(":terminal", s(Phase.TERMINAL.name()));
            condition += " AND (attribute_not_exists(phase) OR phase <> :terminal)";
        }
        String expression = "SET phase = :phase, offerCount = :offerCount, updatedAt = :now";
        if (outcome != null) {
            values.put(":outcome", s(outcome));
            expression += ", outcome = :outcome";
        } else {
            expression += " REMOVE outcome";
        }
        return updateIf(update(QuoteKeys.quotePk(quoteId), QuoteKeys.META)
                .updateExpression(expression)
                .conditionExpression(condition)
                .expressionAttributeValues(values)
                .build());
    }

    /**
     * Grava ou atualiza a ocorrência, sem condição: a mesma situação observada de novo atualiza
     * {@code lastSeenAt} e incrementa o {@code revision}, e {@code firstSeenAt} fica o da primeira vez.
     */
    TransactWriteItem issueWrite(QuoteIssue issue, Instant now) {
        Map<String, String> names = Map.of("#type", QuoteKeys.TYPE, "#status", "status", "#source", "source");
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":type", s(QuoteKeys.TYPE_ISSUE),
                ":issueType", s(issue.type().name()),
                ":open", s(QuoteIssues.Status.OPEN.name()),
                ":source", s(QuoteIssues.SOURCE),
                ":issueId", s(issue.issueId()),
                ":summary", s(issue.safeSummary()),
                ":reconcilable", AttributeValue.fromBool(false),
                ":now", s(now.toString()),
                ":zero", n(0),
                ":one", n(1)));
        StringBuilder expression = new StringBuilder("SET #type = :type, issueType = :issueType, #status = :open, "
                + "#source = :source, "
                + "issueId = :issueId, safeSummary = :summary, reconcilable = :reconcilable, "
                + "firstSeenAt = if_not_exists(firstSeenAt, :now), lastSeenAt = :now, "
                + "revision = if_not_exists(revision, :zero) + :one");
        if (issue.organizationId() != null) {
            values.put(":org", s(issue.organizationId()));
            values.put(":gsi1pk", s(QuoteKeys.issuesIndexPk(issue.organizationId())));
            values.put(":gsi1sk", s(now + "#" + issue.issueId()));
            expression.append(", organizationId = :org, ").append(QuoteKeys.GSI1_PK).append(" = :gsi1pk, ")
                    .append(QuoteKeys.GSI1_SK).append(" = :gsi1sk");
        }
        if (issue.quoteId() != null) {
            values.put(":quoteId", s(issue.quoteId()));
            expression.append(", quoteId = :quoteId");
        }
        if (issue.version() != null) {
            values.put(":version", n(issue.version()));
            expression.append(", version = :version");
        }
        if (issue.queryId() != null) {
            values.put(":queryId", s(issue.queryId()));
            expression.append(", queryId = :queryId");
        }
        return update(QuoteKeys.issuePk(issue.issueId()), QuoteKeys.META, expression.toString(), null, names, values);
    }

    void writeIssue(QuoteIssue issue, Instant now) {
        transact(List.of(issueWrite(issue, now)));
    }

    /** Organização da cotação, lida do {@code META}; nula quando a cotação não existe. */
    String organizationOf(String quoteId) {
        return get(QuoteKeys.quotePk(quoteId), QuoteKeys.META).map(meta -> Attr.string(meta, "organizationId"))
                .orElse(null);
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

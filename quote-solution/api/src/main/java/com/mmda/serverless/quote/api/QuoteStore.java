package com.mmda.serverless.quote.api;

import static com.mmda.serverless.quote.api.Items.n;
import static com.mmda.serverless.quote.api.Items.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Idempotency;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Retries;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Acesso da API à tabela da quote-solution (§4 e §8 do desenho). */
@ApplicationScoped
public class QuoteStore {

    private static final String NOT_EXISTS = "attribute_not_exists(" + QuoteKeys.PK + ")";

    private final DynamoDbClient dynamoDb;
    private final ObjectMapper objectMapper;
    private final QuoteApiConfig config;
    private final Retries retries;

    @Inject
    QuoteStore(DynamoDbClient dynamoDb, ObjectMapper objectMapper, QuoteApiConfig config) {
        this(dynamoDb, objectMapper, config, Retries.transactionConflicts());
    }

    QuoteStore(DynamoDbClient dynamoDb, ObjectMapper objectMapper, QuoteApiConfig config, Retries retries) {
        this.dynamoDb = dynamoDb;
        this.objectMapper = objectMapper;
        this.config = config;
        this.retries = retries;
    }

    /** Dados da cotação nova; o {@code snapshot} já foi resolvido no catálogo da organização. */
    public record NewQuote(
            Idempotency.Key idempotencyKey,
            String requestHash,
            String quoteId,
            String organizationId,
            String createdBy,
            String snapshotJson,
            String inputHash,
            Instant acceptedAt,
            Instant deadlineAt) {
    }

    /** Identidade devolvida ao cliente: a criada agora ou a de um reenvio idêntico. */
    public record CreatedQuote(String quoteId, int version, String phase) {
    }

    /**
     * Grava, numa transação, idempotência, {@code META}, {@code V#0001} e o outbox
     * {@code StartQuoteRequested} e {@code QuoteAccepted}. Conflito da chave relê o registro e compara o
     * hash (§8 do desenho).
     */
    public CreatedQuote create(NewQuote quote) {
        try {
            return createOrReplay(quote);
        } catch (SdkClientException | AwsServiceException failure) {
            if (failure instanceof AwsServiceException service && !service.isThrottlingException()
                    && service.statusCode() < 500) {
                throw failure;
            }
            // WHY: a gravação pode ter acontecido (timeout depois do envio); o reenvio com a mesma chave relê o
            // registro de idempotência e devolve a mesma cotação
            throw unavailable();
        }
    }

    private CreatedQuote createOrReplay(NewQuote quote) {
        List<TransactWriteItem> writes = creationWrites(quote);
        try {
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(writes).build();
            retries.run(() -> dynamoDb.transactWriteItems(request), QuoteStore::isTransactionConflict);
            return new CreatedQuote(quote.quoteId(), 1, Phase.RECEIVED.name());
        } catch (TransactionCanceledException canceled) {
            // INVARIANT: a transação cancelada não gravou nada e, sem o registro de idempotência, nenhuma outra
            // criação com a chave foi aceita: é indisponibilidade antes da aceitação durável (503)
            Map<String, AttributeValue> existing = idempotencyRecord(quote.idempotencyKey())
                    .orElseThrow(QuoteStore::unavailable);
            if (Idempotency.compare(Items.string(existing, "requestHash"), quote.requestHash())
                    == Idempotency.Decision.CONFLICT) {
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT",
                        "A Idempotency-Key já foi usada com outro conteúdo.");
            }
            String quoteId = Items.string(existing, "quoteId");
            int version = (int) Items.number(existing, "version", 1);
            String phase = versionItem(quoteId, version)
                    .map(item -> Items.string(item, "phase"))
                    .orElse(Phase.RECEIVED.name());
            return new CreatedQuote(quoteId, version, phase);
        }
    }

    private List<TransactWriteItem> creationWrites(NewQuote quote) {
        String now = quote.acceptedAt().toString();
        String pk = QuoteKeys.quotePk(quote.quoteId());
        Map<String, AttributeValue> idempotency = new HashMap<>();
        idempotency.put(QuoteKeys.PK, s(quote.idempotencyKey().pk()));
        idempotency.put(QuoteKeys.SK, s(QuoteKeys.META));
        idempotency.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_IDEMPOTENCY));
        idempotency.put("organizationId", s(quote.organizationId()));
        idempotency.put("operation", s(quote.idempotencyKey().operation().name()));
        idempotency.put("scope", s(quote.idempotencyKey().scope()));
        idempotency.put("requestHash", s(quote.requestHash()));
        idempotency.put("resourceRef", s(QuoteKeys.statusUrl(quote.quoteId(), 1)));
        idempotency.put("quoteId", s(quote.quoteId()));
        idempotency.put("version", n(1));
        idempotency.put("createdAt", s(now));

        Map<String, AttributeValue> meta = new HashMap<>();
        meta.put(QuoteKeys.PK, s(pk));
        meta.put(QuoteKeys.SK, s(QuoteKeys.META));
        meta.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_QUOTE));
        meta.put("quoteId", s(quote.quoteId()));
        meta.put("organizationId", s(quote.organizationId()));
        meta.put("createdBy", s(quote.createdBy()));
        meta.put("currentVersion", n(1));
        meta.put("createdAt", s(now));
        meta.put("updatedAt", s(now));
        meta.put("phase", s(Phase.RECEIVED.name()));
        meta.put("offerCount", n(0));
        meta.put(QuoteKeys.GSI1_PK, s(QuoteKeys.organizationIndexPk(quote.organizationId())));
        meta.put(QuoteKeys.GSI1_SK, s(now + "#" + quote.quoteId()));

        Map<String, AttributeValue> version = new HashMap<>();
        version.put(QuoteKeys.PK, s(pk));
        version.put(QuoteKeys.SK, s(QuoteKeys.versionSk(1)));
        version.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_VERSION));
        version.put("quoteId", s(quote.quoteId()));
        version.put("version", n(1));
        version.put("snapshot", s(quote.snapshotJson()));
        version.put("inputHash", s(quote.inputHash()));
        version.put("phase", s(Phase.RECEIVED.name()));
        version.put("revision", n(1));
        version.put("workflowVersion", s(config.workflowVersion()));
        version.put("executionName", s(QuoteKeys.executionName(quote.quoteId(), 1)));
        version.put("deadlineAt", s(quote.deadlineAt().toString()));
        version.put("createdAt", s(now));
        version.put("updatedAt", s(now));
        version.put(QuoteKeys.GSI2_PK, s(QuoteKeys.ACTIVE_VERSION));
        version.put(QuoteKeys.GSI2_SK, s(quote.deadlineAt().toString()));

        String startEventId = UUID.randomUUID().toString();
        String acceptedEventId = UUID.randomUUID().toString();
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(put(idempotency));
        writes.add(put(meta));
        writes.add(put(version));
        writes.add(put(outbox(startEventId, QuoteEvents.START_QUOTE_REQUESTED, quote, QuoteEvents.startQuoteRequested(
                startEventId, quote.acceptedAt(), quote.quoteId(), 1, quote.organizationId()))));
        writes.add(put(outbox(acceptedEventId, QuoteEvents.QUOTE_ACCEPTED, quote, QuoteEvents.quoteAccepted(
                acceptedEventId, quote.acceptedAt(), quote.quoteId(), 1, quote.organizationId()))));
        return writes;
    }

    private Map<String, AttributeValue> outbox(String eventId, String detailType, NewQuote quote,
            Map<String, Object> detail) {
        String now = quote.acceptedAt().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(QuoteKeys.PK, s(QuoteKeys.outboxPk(eventId)));
        item.put(QuoteKeys.SK, s(QuoteKeys.META));
        item.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_OUTBOX));
        item.put("eventId", s(eventId));
        item.put("detailType", s(detailType));
        item.put("detail", s(toJson(detail)));
        item.put("quoteId", s(quote.quoteId()));
        item.put("version", n(1));
        item.put("createdAt", s(now));
        item.put("attempts", n(0));
        item.put(QuoteKeys.GSI2_PK, s(QuoteKeys.OUTBOX_PENDING));
        item.put(QuoteKeys.GSI2_SK, s(now));
        return item;
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(config.tableName()).item(item).conditionExpression(NOT_EXISTS).build())
                .build();
    }

    Optional<Map<String, AttributeValue>> idempotencyRecord(Idempotency.Key key) {
        return getItem(key.pk(), QuoteKeys.META);
    }

    public Optional<Map<String, AttributeValue>> meta(String quoteId) {
        return getItem(QuoteKeys.quotePk(quoteId), QuoteKeys.META);
    }

    Optional<Map<String, AttributeValue>> versionItem(String quoteId, int version) {
        return getItem(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version));
    }

    /** Itens da versão ({@code V#n} e filhos), com leitura consistente para a revisão (§4 do desenho). */
    public List<Map<String, AttributeValue>> versionItems(String quoteId, int version) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryResponse page = dynamoDb.query(QueryRequest.builder()
                    .tableName(config.tableName())
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                    .expressionAttributeNames(Map.of("#pk", QuoteKeys.PK, "#sk", QuoteKeys.SK))
                    .expressionAttributeValues(Map.of(
                            ":pk", s(QuoteKeys.quotePk(quoteId)),
                            ":prefix", s(QuoteKeys.versionSk(version))))
                    .consistentRead(true)
                    .exclusiveStartKey(startKey)
                    .build());
            items.addAll(page.items());
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return items;
    }

    private Optional<Map<String, AttributeValue>> getItem(String pk, String sk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(config.tableName())
                .key(Map.of(QuoteKeys.PK, s(pk), QuoteKeys.SK, s(sk)))
                .consistentRead(true)
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    static boolean isTransactionConflict(RuntimeException failure) {
        return failure instanceof TransactionCanceledException canceled
                && canceled.hasCancellationReasons()
                && canceled.cancellationReasons().stream()
                        .map(CancellationReason::code)
                        .anyMatch("TransactionConflict"::equals);
    }

    private static ApiException unavailable() {
        return new ApiException(503, "SERVICE_UNAVAILABLE",
                "Não foi possível aceitar a cotação agora; reenvie com a mesma Idempotency-Key.");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

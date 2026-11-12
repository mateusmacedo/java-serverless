package com.mmda.serverless.quote.api;

import static com.mmda.serverless.quote.api.Items.n;
import static com.mmda.serverless.quote.api.Items.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Idempotency;
import com.mmda.serverless.quote.domain.Operations;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Acesso da API aos itens operacionais (§4, §8 e §11 do desenho, M5): ocorrências no {@code ISSUE#}, consultas
 * conhecidas no {@code QUERYREF#} e reconciliações no {@code OPERATION#}. Toda leitura confere a organização.
 */
@ApplicationScoped
public class OperationsStore {

    private static final String NOT_EXISTS = "attribute_not_exists(" + QuoteKeys.PK + ")";
    private static final String UNAVAILABLE =
            "Não foi possível registrar a reconciliação agora; reenvie com a mesma Idempotency-Key.";
    static final String QUEUED_SUMMARY = "Pedido de reconciliação registrado; aguardando a provider.";

    private final DynamoDbClient dynamoDb;
    private final ObjectMapper objectMapper;
    private final QuoteApiConfig config;
    private final Retries retries;

    @Inject
    OperationsStore(DynamoDbClient dynamoDb, ObjectMapper objectMapper, QuoteApiConfig config) {
        this(dynamoDb, objectMapper, config, Retries.transactionConflicts());
    }

    OperationsStore(DynamoDbClient dynamoDb, ObjectMapper objectMapper, QuoteApiConfig config, Retries retries) {
        this.dynamoDb = dynamoDb;
        this.objectMapper = objectMapper;
        this.config = config;
        this.retries = retries;
    }

    /** Filtros da listagem de ocorrências; nulos são ignorados. */
    public record IssueFilter(Operations.IssueType type, String status) {
    }

    /**
     * Ocorrências da organização pelo {@code gsi1} ({@code ISSUES#<organizationId>}), as vistas por último primeiro,
     * com {@code issueType} e {@code status} em {@code FilterExpression}, sem {@code Scan} (§11 do desenho).
     */
    public IndexPages.Page issues(String organizationId, IssueFilter filter, String afterPosition, int limit) {
        List<String> conditions = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        if (filter.type() != null) {
            conditions.add("issueType = :issueType");
            values.put(":issueType", s(filter.type().name()));
        }
        if (filter.status() != null) {
            conditions.add("#status = :status");
            names.put("#status", "status");
            values.put(":status", s(filter.status()));
        }
        return IndexPages.read(dynamoDb, config.tableName(), QuoteKeys.issuesIndexPk(organizationId),
                new IndexPages.Filter(null, conditions.isEmpty() ? null : String.join(" AND ", conditions), names,
                        values, item -> true),
                afterPosition, position -> Map.of(QuoteKeys.PK, s(QuoteKeys.issuePk(IndexPages.idOf(position))),
                        QuoteKeys.SK, s(QuoteKeys.META)), limit);
    }

    /** A consulta é conhecida da organização só quando o {@code issue-projector} gravou o {@code QUERYREF#} dela. */
    public boolean knownQuery(String queryId, String organizationId) {
        if (!Items.fitsKey(queryId)) {
            return false;
        }
        return getItem(QuoteKeys.queryRefPk(queryId), QuoteKeys.META)
                .filter(item -> organizationId.equals(Items.string(item, "organizationId")))
                .isPresent();
    }

    /** Reconciliação da organização; a de outra organização é tratada como inexistente. */
    public Optional<Map<String, AttributeValue>> operation(String operationId, String organizationId) {
        if (!Items.fitsKey(operationId)) {
            return Optional.empty();
        }
        return getItem(QuoteKeys.operationPk(operationId), QuoteKeys.META)
                .filter(item -> organizationId.equals(Items.string(item, "organizationId")));
    }

    public record NewReconcile(Idempotency.Key idempotencyKey, String requestHash, String operationId,
            String organizationId, String queryId, String reason, String requestedBy, Instant requestedAt) {
    }

    /** Operação aceita: a criada agora ou a de um reenvio idêntico; a situação segue no {@code statusUrl}. */
    public record AcceptedOperation(String operationId) {
    }

    /**
     * Grava, numa transação, idempotência ({@code RECONCILE}, escopo {@code queryId}), {@code OPERATION#} em
     * {@code QUEUED} com o motivo e quem pediu, e o outbox {@code QueryReconcileRequested} (§8 do desenho).
     */
    public AcceptedOperation request(NewReconcile reconcile) {
        try {
            return requestOrReplay(reconcile);
        } catch (SdkClientException | AwsServiceException failure) {
            if (failure instanceof AwsServiceException service && !service.isThrottlingException()
                    && service.statusCode() < 500) {
                throw failure;
            }
            throw QuoteStore.unavailable(UNAVAILABLE);
        }
    }

    private AcceptedOperation requestOrReplay(NewReconcile reconcile) {
        Optional<Map<String, AttributeValue>> recorded = getItem(reconcile.idempotencyKey().pk(), QuoteKeys.META);
        if (recorded.isPresent()) {
            return replay(recorded.get(), reconcile);
        }
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(writes(reconcile)).build();
        try {
            retries.run(() -> dynamoDb.transactWriteItems(request), QuoteStore::isTransactionConflict);
            return new AcceptedOperation(reconcile.operationId());
        } catch (TransactionCanceledException canceled) {
            return replay(getItem(reconcile.idempotencyKey().pk(), QuoteKeys.META)
                    .orElseThrow(() -> QuoteStore.unavailable(UNAVAILABLE)), reconcile);
        }
    }

    private AcceptedOperation replay(Map<String, AttributeValue> recorded, NewReconcile reconcile) {
        if (Idempotency.compare(Items.string(recorded, "requestHash"), reconcile.requestHash())
                == Idempotency.Decision.CONFLICT) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "A Idempotency-Key já foi usada com outro conteúdo.");
        }
        return new AcceptedOperation(Items.string(recorded, "operationId"));
    }

    private List<TransactWriteItem> writes(NewReconcile reconcile) {
        String now = reconcile.requestedAt().toString();
        Map<String, AttributeValue> idempotency = new HashMap<>();
        idempotency.put(QuoteKeys.PK, s(reconcile.idempotencyKey().pk()));
        idempotency.put(QuoteKeys.SK, s(QuoteKeys.META));
        idempotency.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_IDEMPOTENCY));
        idempotency.put("organizationId", s(reconcile.organizationId()));
        idempotency.put("operation", s(reconcile.idempotencyKey().operation().name()));
        idempotency.put("scope", s(reconcile.idempotencyKey().scope()));
        idempotency.put("requestHash", s(reconcile.requestHash()));
        idempotency.put("resourceRef", s(QuoteKeys.operationStatusUrl(reconcile.operationId())));
        idempotency.put("operationId", s(reconcile.operationId()));
        idempotency.put("queryId", s(reconcile.queryId()));
        idempotency.put("createdAt", s(now));

        Map<String, AttributeValue> operation = new HashMap<>();
        operation.put(QuoteKeys.PK, s(QuoteKeys.operationPk(reconcile.operationId())));
        operation.put(QuoteKeys.SK, s(QuoteKeys.META));
        operation.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_OPERATION));
        operation.put("operationId", s(reconcile.operationId()));
        operation.put("organizationId", s(reconcile.organizationId()));
        operation.put("queryId", s(reconcile.queryId()));
        operation.put("reason", s(reconcile.reason()));
        operation.put("requestedBy", s(reconcile.requestedBy()));
        operation.put("requestedAt", s(now));
        operation.put("status", s(Operations.OperationStatus.QUEUED.name()));
        operation.put("safeSummary", s(QUEUED_SUMMARY));
        // INVARIANT: o QueryReconciled da provider chega com revision 2 e substitui este QUEUED (§3 do desenho)
        operation.put("revision", n(0));
        operation.put("updatedAt", s(now));

        String eventId = UUID.randomUUID().toString();
        Map<String, AttributeValue> outbox = new HashMap<>();
        outbox.put(QuoteKeys.PK, s(QuoteKeys.outboxPk(eventId)));
        outbox.put(QuoteKeys.SK, s(QuoteKeys.META));
        outbox.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_OUTBOX));
        outbox.put("eventId", s(eventId));
        outbox.put("detailType", s(QuoteEvents.QUERY_RECONCILE_REQUESTED));
        outbox.put("detail", s(toJson(QuoteEvents.queryReconcileRequested(eventId, reconcile.requestedAt(),
                reconcile.operationId(), reconcile.organizationId(), reconcile.queryId(), reconcile.reason(),
                reconcile.requestedBy()))));
        outbox.put("operationId", s(reconcile.operationId()));
        outbox.put("createdAt", s(now));
        outbox.put("attempts", n(0));
        outbox.put(QuoteKeys.GSI2_PK, s(QuoteKeys.OUTBOX_PENDING));
        outbox.put(QuoteKeys.GSI2_SK, s(now));
        return List.of(put(idempotency), put(operation), put(outbox));
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(config.tableName()).item(item).conditionExpression(NOT_EXISTS).build())
                .build();
    }

    private Optional<Map<String, AttributeValue>> getItem(String pk, String sk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(config.tableName())
                .key(Map.of(QuoteKeys.PK, s(pk), QuoteKeys.SK, s(sk)))
                .consistentRead(true)
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

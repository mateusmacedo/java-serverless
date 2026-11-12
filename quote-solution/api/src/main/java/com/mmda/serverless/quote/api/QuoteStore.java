package com.mmda.serverless.quote.api;

import static com.mmda.serverless.quote.api.Items.n;
import static com.mmda.serverless.quote.api.Items.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Idempotency;
import com.mmda.serverless.quote.domain.Outcome;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Retries;
import com.mmda.serverless.quote.domain.Selections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/** Acesso da API à tabela da quote-solution (§4 e §8 do desenho). */
@ApplicationScoped
public class QuoteStore {

    private static final String NOT_EXISTS = "attribute_not_exists(" + QuoteKeys.PK + ")";
    private static final String SELECTION_UNAVAILABLE =
            "Não foi possível registrar a seleção agora; reenvie com a mesma Idempotency-Key.";
    /** Prefixo de segundo de um instante, que limita o período no {@code gsi1sk}. */
    private static final DateTimeFormatter SECOND =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(put(idempotency));
        writes.add(put(meta));
        writes.add(put(versionItem(quote.quoteId(), 1, quote.snapshotJson(), quote.inputHash(), quote.acceptedAt(),
                quote.deadlineAt())));
        writes.addAll(startOutbox(quote.quoteId(), 1, quote.organizationId(), quote.acceptedAt()));
        return writes;
    }

    /** Dados da nova versão; o {@code snapshot} já foi resolvido no catálogo da organização. */
    public record NewVersion(
            Idempotency.Key idempotencyKey,
            String requestHash,
            String quoteId,
            int expectedVersion,
            String organizationId,
            String snapshotJson,
            String inputHash,
            Instant acceptedAt,
            Instant deadlineAt) {

        int version() {
            return expectedVersion + 1;
        }
    }

    /**
     * Nova versão (§8 do desenho, T17): uma transação grava a idempotência, troca o {@code currentVersion} do
     * {@code META}, marca {@code supersededAt} na versão substituída e grava {@code V#n} com o outbox de início.
     * A condição do {@code META} serializa a nova versão com a seleção (T29); a versão antiga segue até o fim.
     */
    public CreatedQuote createVersion(NewVersion version) {
        try {
            return createVersionOrReplay(version);
        } catch (SdkClientException | AwsServiceException failure) {
            if (failure instanceof AwsServiceException service && !service.isThrottlingException()
                    && service.statusCode() < 500) {
                throw failure;
            }
            throw unavailable();
        }
    }

    private CreatedQuote createVersionOrReplay(NewVersion version) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(versionWrites(version)).build();
        try {
            retries.run(() -> dynamoDb.transactWriteItems(request), QuoteStore::isTransactionConflict);
            return new CreatedQuote(version.quoteId(), version.version(), Phase.RECEIVED.name());
        } catch (TransactionCanceledException canceled) {
            Optional<Map<String, AttributeValue>> existing = idempotencyRecord(version.idempotencyKey());
            if (existing.isPresent()) {
                return replay(existing.get(), version.requestHash());
            }
            if (!metaConditionFailed(canceled)) {
                throw unavailable();
            }
            Map<String, AttributeValue> meta = meta(version.quoteId()).orElseThrow(ApiException::notFound);
            if (Items.string(meta, "selectionId") != null) {
                throw new ApiException(409, "QUOTE_ALREADY_SELECTED",
                        "A cotação já tem uma oferta selecionada e não aceita nova versão.");
            }
            throw new ApiException(409, "VERSION_CONFLICT",
                    "A versão informada em expectedVersion não é mais a atual; leia a cotação e tente de novo.");
        }
    }

    /** Mesmo hash devolve a identidade gravada; hash diferente é 409 {@code IDEMPOTENCY_CONFLICT} (§8). */
    private CreatedQuote replay(Map<String, AttributeValue> existing, String requestHash) {
        if (Idempotency.compare(Items.string(existing, "requestHash"), requestHash) == Idempotency.Decision.CONFLICT) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "A Idempotency-Key já foi usada com outro conteúdo.");
        }
        String quoteId = Items.string(existing, "quoteId");
        int number = (int) Items.number(existing, "version", 1);
        String phase = versionItem(quoteId, number)
                .map(item -> Items.string(item, "phase"))
                .orElse(Phase.RECEIVED.name());
        return new CreatedQuote(quoteId, number, phase);
    }

    /** A condição do {@code META} é a segunda ação da transação da nova versão. */
    private static boolean metaConditionFailed(TransactionCanceledException canceled) {
        List<CancellationReason> reasons = canceled.hasCancellationReasons() ? canceled.cancellationReasons() : List.of();
        return reasons.size() > 1 && "ConditionalCheckFailed".equals(reasons.get(1).code());
    }

    private List<TransactWriteItem> versionWrites(NewVersion version) {
        String now = version.acceptedAt().toString();
        String pk = QuoteKeys.quotePk(version.quoteId());
        Map<String, AttributeValue> idempotency = new HashMap<>();
        idempotency.put(QuoteKeys.PK, s(version.idempotencyKey().pk()));
        idempotency.put(QuoteKeys.SK, s(QuoteKeys.META));
        idempotency.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_IDEMPOTENCY));
        idempotency.put("organizationId", s(version.organizationId()));
        idempotency.put("operation", s(version.idempotencyKey().operation().name()));
        idempotency.put("scope", s(version.idempotencyKey().scope()));
        idempotency.put("requestHash", s(version.requestHash()));
        idempotency.put("resourceRef", s(QuoteKeys.statusUrl(version.quoteId(), version.version())));
        idempotency.put("quoteId", s(version.quoteId()));
        idempotency.put("version", n(version.version()));
        idempotency.put("createdAt", s(now));
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(put(idempotency));
        writes.add(update(pk, QuoteKeys.META,
                "SET currentVersion = :next, phase = :received, offerCount = :zero, updatedAt = :now REMOVE outcome",
                "currentVersion = :expected AND attribute_not_exists(selectionId) AND organizationId = :org",
                Map.of(":next", n(version.version()), ":received", s(Phase.RECEIVED.name()), ":zero", n(0),
                        ":now", s(now), ":expected", n(version.expectedVersion()),
                        ":org", s(version.organizationId()))));
        writes.add(update(pk, QuoteKeys.versionSk(version.expectedVersion()),
                "SET supersededAt = :now, revision = revision + :one, updatedAt = :now",
                "attribute_exists(" + QuoteKeys.PK + ")",
                Map.of(":now", s(now), ":one", n(1))));
        writes.add(put(versionItem(version.quoteId(), version.version(), version.snapshotJson(), version.inputHash(),
                version.acceptedAt(), version.deadlineAt())));
        writes.addAll(startOutbox(version.quoteId(), version.version(), version.organizationId(), version.acceptedAt()));
        return writes;
    }

    /** Filtros da listagem; nulos são ignorados. O período é inclusivo nos dois extremos. */
    public record QuoteFilter(Phase phase, Outcome outcome, Instant createdFrom, Instant createdTo) {
    }

    /**
     * Cotações da organização pelo {@code gsi1} ({@code ORG#<organizationId>}), as mais novas primeiro, com
     * {@code phase} e {@code outcome} do resumo do {@code META} em {@code FilterExpression} (§4 do desenho).
     * WHY: o {@code createdAt} tem precisão variável no {@code gsi1sk}; a chave limita o período ao segundo e o
     * instante exato é conferido no código.
     */
    public IndexPages.Page listQuotes(String organizationId, QuoteFilter filter, String afterPosition, int limit) {
        List<String> conditions = new ArrayList<>();
        Map<String, AttributeValue> values = new HashMap<>();
        if (filter.phase() != null) {
            conditions.add("phase = :phase");
            values.put(":phase", s(filter.phase().name()));
        }
        if (filter.outcome() != null) {
            conditions.add("outcome = :outcome");
            values.put(":outcome", s(filter.outcome().name()));
        }
        String sortKey = null;
        String from = filter.createdFrom() == null ? null : SECOND.format(filter.createdFrom());
        String to = filter.createdTo() == null ? null : SECOND.format(filter.createdTo()) + "~";
        if (from != null) {
            values.put(":from", s(from));
            sortKey = "#gsk >= :from";
        }
        if (to != null) {
            values.put(":to", s(to));
            sortKey = sortKey == null ? "#gsk <= :to" : "#gsk BETWEEN :from AND :to";
        }
        // WHY: o DynamoDB recusa com ValidationException o ExclusiveStartKey fora da condição da chave; o cursor de
        // outra combinação de filtros responde 400, como o contrato prevê
        if (afterPosition != null && ((from != null && afterPosition.compareTo(from) < 0)
                || (to != null && afterPosition.compareTo(to) > 0))) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("cursor", "Cursor inválido.")));
        }
        IndexPages.Filter indexFilter = new IndexPages.Filter(sortKey,
                conditions.isEmpty() ? null : String.join(" AND ", conditions), Map.of(), values,
                meta -> createdWithin(meta, filter));
        return IndexPages.read(dynamoDb, config.tableName(), QuoteKeys.organizationIndexPk(organizationId), indexFilter,
                afterPosition, position -> Map.of(QuoteKeys.PK, s(QuoteKeys.quotePk(IndexPages.idOf(position))),
                        QuoteKeys.SK, s(QuoteKeys.META)), limit);
    }

    private static boolean createdWithin(Map<String, AttributeValue> meta, QuoteFilter filter) {
        String createdAt = Items.string(meta, "createdAt");
        if (createdAt == null) {
            return false;
        }
        Instant created = Instant.parse(createdAt);
        return (filter.createdFrom() == null || !created.isBefore(filter.createdFrom()))
                && (filter.createdTo() == null || !created.isAfter(filter.createdTo()));
    }

    /** Pedido de seleção, depois da permissão e da organização conferidas pela API. */
    public record NewSelection(Idempotency.Key idempotencyKey, String requestHash, String quoteId, int version,
            String offerId, String organizationId, String selectedBy, Instant selectedAt) {
    }

    /** Seleção devolvida ao cliente: a gravada agora, a de um reenvio ou a existente com a mesma oferta. */
    public record RecordedSelection(String selectionId, String offerId, String selectedAt) {
    }

    /**
     * Seleção definitiva (§8 do desenho, RN13, T18, T28 e T29). A decisão sai do {@code META} e da oferta lidos agora;
     * a transação grava idempotência, {@code SELECTION}, {@code META} e outbox {@code OfferSelected} e confere a
     * oferta. A condição do {@code META} é a mesma da nova versão, então só uma das duas vence.
     */
    public RecordedSelection select(NewSelection selection, Map<String, AttributeValue> meta) {
        try {
            return selectOrReplay(selection, meta);
        } catch (SdkClientException | AwsServiceException failure) {
            if (failure instanceof AwsServiceException service && !service.isThrottlingException()
                    && service.statusCode() < 500) {
                throw failure;
            }
            throw unavailable(SELECTION_UNAVAILABLE);
        }
    }

    private RecordedSelection selectOrReplay(NewSelection selection, Map<String, AttributeValue> meta) {
        Optional<Map<String, AttributeValue>> recorded = idempotencyRecord(selection.idempotencyKey());
        if (recorded.isPresent()) {
            return replaySelection(recorded.get(), selection.requestHash());
        }
        Optional<Map<String, AttributeValue>> offer = getItem(QuoteKeys.quotePk(selection.quoteId()),
                QuoteKeys.offerSk(selection.version(), selection.offerId()));
        Selections.Decision decision = decide(selection, meta, offer);
        if (decision != Selections.Decision.RECORD) {
            return answer(decision, selection);
        }
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(selectionWrites(selection)).build();
        try {
            retries.run(() -> dynamoDb.transactWriteItems(request), QuoteStore::isTransactionConflict);
            return new RecordedSelection(QuoteKeys.selectionId(selection.quoteId(), selection.version()),
                    selection.offerId(), selection.selectedAt().toString());
        } catch (TransactionCanceledException canceled) {
            recorded = idempotencyRecord(selection.idempotencyKey());
            if (recorded.isPresent()) {
                return replaySelection(recorded.get(), selection.requestHash());
            }
            List<String> reasons = canceled.hasCancellationReasons()
                    ? canceled.cancellationReasons().stream().map(CancellationReason::code).toList() : List.of();
            if (failed(reasons, 1) || failed(reasons, 2)) {
                // NOTE: outra seleção ou uma nova versão venceu a corrida (T29); a decisão relê o META
                Map<String, AttributeValue> current = meta(selection.quoteId()).orElseThrow(ApiException::notFound);
                Selections.Decision after = decide(selection, current, offer);
                if (after != Selections.Decision.RECORD) {
                    return answer(after, selection);
                }
            } else if (failed(reasons, 3)) {
                return answer(Selections.Decision.OFFER_EXPIRED, selection);
            }
            throw unavailable(SELECTION_UNAVAILABLE);
        }
    }

    private Selections.Decision decide(NewSelection selection, Map<String, AttributeValue> meta,
            Optional<Map<String, AttributeValue>> offer) {
        String selectedOfferId = Items.string(meta, "selectedOfferId");
        Selections.Existing existing = Items.string(meta, "selectionId") == null || selectedOfferId == null ? null
                : new Selections.Existing((int) Items.number(meta, "selectedVersion", 0), selectedOfferId);
        String validUntil = offer.map(item -> Items.string(item, "validUntil")).orElse(null);
        return Selections.decide(selection.version(), (int) Items.number(meta, "currentVersion", 1), existing,
                selection.offerId(), offer.isPresent(), validUntil == null ? null : Instant.parse(validUntil),
                selection.selectedAt());
    }

    private RecordedSelection answer(Selections.Decision decision, NewSelection selection) {
        return switch (decision) {
            case EXISTING -> getItem(QuoteKeys.quotePk(selection.quoteId()), QuoteKeys.selectionSk(selection.version()))
                    .map(item -> new RecordedSelection(Items.string(item, "selectionId"), Items.string(item, "offerId"),
                            Items.string(item, "selectedAt")))
                    .orElseThrow(() -> unavailable(SELECTION_UNAVAILABLE));
            case NOT_FOUND -> throw ApiException.notFound();
            case VERSION_SUPERSEDED -> throw new ApiException(409, "VERSION_SUPERSEDED",
                    "A versão foi substituída por uma mais nova; leia a versão atual da cotação.");
            case SELECTION_ALREADY_EXISTS -> throw new ApiException(409, "SELECTION_ALREADY_EXISTS",
                    "A cotação já tem outra oferta selecionada; a seleção é definitiva.");
            case OFFER_EXPIRED -> throw new ApiException(409, "OFFER_EXPIRED",
                    "A oferta expirou; atualize as ofertas da versão.");
            case RECORD -> throw unavailable(SELECTION_UNAVAILABLE);
        };
    }

    /** Mesmo hash devolve a seleção gravada pela chave; hash diferente é 409 {@code IDEMPOTENCY_CONFLICT}. */
    private static RecordedSelection replaySelection(Map<String, AttributeValue> recorded, String requestHash) {
        if (Idempotency.compare(Items.string(recorded, "requestHash"), requestHash) == Idempotency.Decision.CONFLICT) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "A Idempotency-Key já foi usada com outro conteúdo.");
        }
        return new RecordedSelection(Items.string(recorded, "selectionId"), Items.string(recorded, "offerId"),
                Items.string(recorded, "selectedAt"));
    }

    private static boolean failed(List<String> reasons, int action) {
        return reasons.size() > action && "ConditionalCheckFailed".equals(reasons.get(action));
    }

    /** Ordem das ações: idempotência, {@code SELECTION}, {@code META}, conferência da oferta e outbox. */
    private List<TransactWriteItem> selectionWrites(NewSelection selection) {
        String now = selection.selectedAt().toString();
        String pk = QuoteKeys.quotePk(selection.quoteId());
        String selectionId = QuoteKeys.selectionId(selection.quoteId(), selection.version());
        Map<String, AttributeValue> idempotency = new HashMap<>();
        idempotency.put(QuoteKeys.PK, s(selection.idempotencyKey().pk()));
        idempotency.put(QuoteKeys.SK, s(QuoteKeys.META));
        idempotency.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_IDEMPOTENCY));
        idempotency.put("organizationId", s(selection.organizationId()));
        idempotency.put("operation", s(selection.idempotencyKey().operation().name()));
        idempotency.put("scope", s(selection.idempotencyKey().scope()));
        idempotency.put("requestHash", s(selection.requestHash()));
        idempotency.put("resourceRef", s(QuoteKeys.statusUrl(selection.quoteId(), selection.version())));
        idempotency.put("quoteId", s(selection.quoteId()));
        idempotency.put("version", n(selection.version()));
        idempotency.put("selectionId", s(selectionId));
        idempotency.put("offerId", s(selection.offerId()));
        idempotency.put("selectedAt", s(now));
        idempotency.put("createdAt", s(now));

        Map<String, AttributeValue> selectionItem = new HashMap<>();
        selectionItem.put(QuoteKeys.PK, s(pk));
        selectionItem.put(QuoteKeys.SK, s(QuoteKeys.selectionSk(selection.version())));
        selectionItem.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_SELECTION));
        selectionItem.put("quoteId", s(selection.quoteId()));
        selectionItem.put("version", n(selection.version()));
        selectionItem.put("selectionId", s(selectionId));
        selectionItem.put("offerId", s(selection.offerId()));
        selectionItem.put("selectedAt", s(now));
        selectionItem.put("selectedBy", s(selection.selectedBy()));

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(put(idempotency));
        writes.add(put(selectionItem));
        writes.add(update(pk, QuoteKeys.META,
                "SET selectionId = :selectionId, selectedVersion = :v, selectedOfferId = :offerId, updatedAt = :now",
                "currentVersion = :v AND attribute_not_exists(selectionId) AND organizationId = :org",
                Map.of(":selectionId", s(selectionId), ":v", n(selection.version()), ":offerId", s(selection.offerId()),
                        ":now", s(now), ":org", s(selection.organizationId()))));
        writes.add(TransactWriteItem.builder().conditionCheck(ConditionCheck.builder()
                .tableName(config.tableName())
                .key(Map.of(QuoteKeys.PK, s(pk), QuoteKeys.SK, s(QuoteKeys.offerSk(selection.version(),
                        selection.offerId()))))
                .conditionExpression("attribute_exists(" + QuoteKeys.PK + ") AND validUntil > :now")
                .expressionAttributeValues(Map.of(":now", s(now)))
                .build()).build());
        Map<String, Object> selected = QuoteEvents.offerSelected(selection.selectedAt(), selection.quoteId(),
                selection.version(), selection.offerId(), selectionId);
        writes.add(put(outbox((String) selected.get("eventId"), QuoteEvents.OFFER_SELECTED, selection.quoteId(),
                selection.version(), selection.selectedAt(), selected)));
        return writes;
    }

    private Map<String, AttributeValue> versionItem(String quoteId, int number, String snapshotJson, String inputHash,
            Instant acceptedAt, Instant deadlineAt) {
        String now = acceptedAt.toString();
        Map<String, AttributeValue> version = new HashMap<>();
        version.put(QuoteKeys.PK, s(QuoteKeys.quotePk(quoteId)));
        version.put(QuoteKeys.SK, s(QuoteKeys.versionSk(number)));
        version.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_VERSION));
        version.put("quoteId", s(quoteId));
        version.put("version", n(number));
        version.put("snapshot", s(snapshotJson));
        version.put("inputHash", s(inputHash));
        version.put("phase", s(Phase.RECEIVED.name()));
        version.put("revision", n(1));
        version.put("workflowVersion", s(config.workflowVersion()));
        version.put("executionName", s(QuoteKeys.executionName(quoteId, number)));
        version.put("deadlineAt", s(deadlineAt.toString()));
        version.put("createdAt", s(now));
        version.put("updatedAt", s(now));
        version.put(QuoteKeys.GSI2_PK, s(QuoteKeys.ACTIVE_VERSION));
        version.put(QuoteKeys.GSI2_SK, s(deadlineAt.toString()));
        return version;
    }

    /** Outbox {@code StartQuoteRequested}, que inicia a execução, e {@code QuoteAccepted} da versão. */
    private List<TransactWriteItem> startOutbox(String quoteId, int version, String organizationId, Instant acceptedAt) {
        String startEventId = UUID.randomUUID().toString();
        String acceptedEventId = UUID.randomUUID().toString();
        return List.of(
                put(outbox(startEventId, QuoteEvents.START_QUOTE_REQUESTED, quoteId, version, acceptedAt,
                        QuoteEvents.startQuoteRequested(startEventId, acceptedAt, quoteId, version, organizationId))),
                put(outbox(acceptedEventId, QuoteEvents.QUOTE_ACCEPTED, quoteId, version, acceptedAt,
                        QuoteEvents.quoteAccepted(acceptedEventId, acceptedAt, quoteId, version, organizationId))));
    }

    private Map<String, AttributeValue> outbox(String eventId, String detailType, String quoteId, int version,
            Instant acceptedAt, Map<String, Object> detail) {
        String now = acceptedAt.toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(QuoteKeys.PK, s(QuoteKeys.outboxPk(eventId)));
        item.put(QuoteKeys.SK, s(QuoteKeys.META));
        item.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_OUTBOX));
        item.put("eventId", s(eventId));
        item.put("detailType", s(detailType));
        item.put("detail", s(toJson(detail)));
        item.put("quoteId", s(quoteId));
        item.put("version", n(version));
        item.put("createdAt", s(now));
        item.put("attempts", n(0));
        item.put(QuoteKeys.GSI2_PK, s(QuoteKeys.OUTBOX_PENDING));
        item.put(QuoteKeys.GSI2_SK, s(now));
        return item;
    }

    private TransactWriteItem update(String pk, String sk, String expression, String condition,
            Map<String, AttributeValue> values) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(config.tableName())
                        .key(Map.of(QuoteKeys.PK, s(pk), QuoteKeys.SK, s(sk)))
                        .updateExpression(expression)
                        .conditionExpression(condition)
                        .expressionAttributeValues(values)
                        .build())
                .build();
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
        if (!Items.fitsKey(quoteId)) {
            return Optional.empty();
        }
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
        return unavailable("Não foi possível aceitar a cotação agora; reenvie com a mesma Idempotency-Key.");
    }

    static ApiException unavailable(String message) {
        return new ApiException(503, "SERVICE_UNAVAILABLE", message);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

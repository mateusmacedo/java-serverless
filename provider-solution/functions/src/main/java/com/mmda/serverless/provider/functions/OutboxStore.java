package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.GSI2;
import static com.mmda.serverless.provider.functions.Items.GSI2PK;
import static com.mmda.serverless.provider.functions.Items.GSI2SK;
import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.OUTBOX_PENDING;
import static com.mmda.serverless.provider.functions.Items.n;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Itens {@code OUTBOX#<eventId>}: gravados na transação do fato, publicados
 * pela relay e, se ficarem
 * {@code OUTBOX_PENDING}, pelo republicador (ADR 0015).
 */
@Component
class OutboxStore {

    private static final int MAX_ERROR_LENGTH = 500;

    private final DynamoDbClient dynamoDb;
    private final EventJson json;
    private final ProviderProperties properties;

    OutboxStore(DynamoDbClient dynamoDb, EventJson json, ProviderProperties properties) {
        this.dynamoDb = dynamoDb;
        this.json = json;
        this.properties = properties;
    }

    /**
     * Put com {@code attribute_not_exists}: o mesmo {@code eventId} nunca vira um
     * segundo evento.
     */
    TransactWriteItem put(Settlement.OutboxEvent event, Instant now) {
        String createdAt = Timestamps.format(now);
        Map<String, AttributeValue> item = Items.item(Items.outboxPk(event.eventId()), META, Items.TYPE_OUTBOX);
        item.put("eventId", s(event.eventId()));
        item.put("detailType", s(event.detailType()));
        item.put("detail", s(json.write(event.detail())));
        item.put("createdAt", s(createdAt));
        item.put("attempts", n(0));
        item.put(GSI2PK, s(OUTBOX_PENDING));
        item.put(GSI2SK, s(createdAt));
        return TransactWriteItem.builder()
                .put(put -> put
                        .tableName(properties.tableName())
                        .item(item)
                        .conditionExpression("attribute_not_exists(pk)"))
                .build();
    }

    /**
     * WHY: {@code SET} absoluto, então publicar de novo um evento já publicado não
     * tem efeito (§7).
     */
    void markPublished(String eventId, Instant now) {
        dynamoDb.updateItem(builder -> builder
                .tableName(properties.tableName())
                .key(Items.key(Items.outboxPk(eventId), META))
                .updateExpression("SET publishedAt = :now REMOVE gsi2pk, gsi2sk")
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeValues(Map.of(":now", s(Timestamps.format(now)))));
    }

    /**
     * Erro permanente: o item continua {@code OUTBOX_PENDING}, com a contagem e o
     * último erro. WHY: o
     * {@code gsi2sk} passa a ser a data da falha, então um item que falha sempre
     * vai para o fim da fila do
     * republicador e não impede a republicação dos mais novos.
     */
    void recordFailure(String eventId, String error, Instant now) {
        String message = error == null ? "unknown" : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        dynamoDb.updateItem(builder -> builder
                .tableName(properties.tableName())
                .key(Items.key(Items.outboxPk(eventId), META))
                .updateExpression("ADD attempts :one SET lastError = :error, lastAttemptAt = :now, gsi2sk = :now")
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeValues(Map.of(
                        ":one", n(1),
                        ":error", s(message),
                        ":now", s(Timestamps.format(now)))));
    }

    /**
     * Todos os pendentes, pela ordem do {@code gsi2sk}, com o {@code createdAt}.
     * WHY: a idade de um item vem do
     * {@code createdAt}, e um item com falha permanente recente tem o
     * {@code gsi2sk} novo, mas pode ser antigo (§7).
     */
    List<PendingOutbox> pending(int limit) {
        return dynamoDb.query(builder -> builder
                .tableName(properties.tableName())
                .indexName(GSI2)
                .keyConditionExpression("gsi2pk = :pending")
                .expressionAttributeValues(Map.of(":pending", s(OUTBOX_PENDING)))
                .limit(limit))
                .items().stream()
                .map(PendingOutbox::from)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Item pendente com o necessário para republicar e medir a idade; {@code entry}
     * é nulo quando o conteúdo não
     * pôde ser lido, e o item só pode virar {@code STALE_OUTBOX}.
     */
    record PendingOutbox(String eventId, OutboxEntry entry, Instant createdAt, String gsi2sk) {

        static Optional<PendingOutbox> from(Map<String, AttributeValue> item) {
            Optional<String> eventId = OutboxEntry.eventIdOf(item);
            Optional<String> createdAt = Items.string(item, "createdAt");
            Optional<String> gsi2sk = Items.string(item, GSI2SK);
            if (eventId.isEmpty() || createdAt.isEmpty() || gsi2sk.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PendingOutbox(eventId.get(), OutboxEntry.from(item).orElse(null),
                    Timestamps.parse(createdAt.get()), gsi2sk.get()));
        }
    }

    /** {@code true} quando o item existe e continua {@code OUTBOX_PENDING}. */
    boolean isPending(String eventId) {
        return Items.string(dynamoDb.getItem(builder -> builder
                .tableName(properties.tableName())
                .consistentRead(true)
                .key(Items.key(Items.outboxPk(eventId), META)))
                .item(), GSI2PK).isPresent();
    }
}

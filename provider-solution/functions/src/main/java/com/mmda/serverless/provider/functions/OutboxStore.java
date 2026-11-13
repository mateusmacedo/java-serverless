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
 * Itens {@code OUTBOX#<eventId>}: gravados na transação do fato, publicados pela relay e, se ficarem
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

    /** Put com {@code attribute_not_exists}: o mesmo {@code eventId} nunca vira um segundo evento. */
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

    /** WHY: {@code SET} absoluto, então publicar de novo um evento já publicado não tem efeito (§7). */
    void markPublished(String eventId, Instant now) {
        dynamoDb.updateItem(builder -> builder
                .tableName(properties.tableName())
                .key(Items.key(Items.outboxPk(eventId), META))
                .updateExpression("SET publishedAt = :now REMOVE gsi2pk, gsi2sk")
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeValues(Map.of(":now", s(Timestamps.format(now)))));
    }

    /**
     * Erro permanente: o item continua {@code OUTBOX_PENDING}, com a contagem e o último erro. WHY: o
     * {@code gsi2sk} passa a ser a data da falha, então um item que falha sempre vai para o fim da fila do
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

    /** Pendentes criados antes de {@code cutoff}, pelo índice esparso {@code gsi2}, mais antigos primeiro. */
    List<OutboxEntry> pendingOlderThan(Instant cutoff, int limit) {
        return dynamoDb.query(builder -> builder
                        .tableName(properties.tableName())
                        .indexName(GSI2)
                        .keyConditionExpression("gsi2pk = :pending AND gsi2sk < :cutoff")
                        .expressionAttributeValues(Map.of(
                                ":pending", s(OUTBOX_PENDING),
                                ":cutoff", s(Timestamps.format(cutoff))))
                        .limit(limit))
                .items().stream()
                .map(OutboxEntry::from)
                .flatMap(Optional::stream)
                .toList();
    }
}

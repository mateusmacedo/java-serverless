package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.s;

import com.mmda.serverless.quote.domain.QuoteKeys;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Outbox pendente (§7 e §9 do desenho): republica o {@code OUTBOX_PENDING} mais
 * antigo que
 * {@code outboxRepublishAfterSeconds} com o mesmo código da relay e mede a
 * idade do mais antigo para a métrica
 * {@code OutboxPendingAgeSeconds}. Publicar de novo não tem efeito: os
 * consumidores descartam o
 * {@code eventId} repetido e a execução repetida conta como sucesso.
 */
@ApplicationScoped
public class OutboxReconciler {

    private static final Logger LOG = Logger.getLogger(OutboxReconciler.class);
    /**
     * WHY: limita a varredura da idade a uma execução do reconciler, mesmo com
     * muito outbox preso.
     */
    private static final int MAX_AGE_PAGES = 10;

    private final DynamoDbClient dynamoDb;
    private final QuoteTable table;
    private final OutboxPublisher publisher;
    private final QuoteFunctionsConfig config;

    OutboxReconciler(DynamoDbClient dynamoDb, QuoteTable table, OutboxPublisher publisher,
            QuoteFunctionsConfig config) {
        this.dynamoDb = dynamoDb;
        this.table = table;
        this.publisher = publisher;
        this.config = config;
    }

    /**
     * Relatório do M1, com {@code scanned}, {@code republished},
     * {@code transientFailures} e {@code permanentFailures}.
     */
    Map<String, Integer> republish(Instant now) {
        Instant cutoff = now.minusSeconds(config.outboxRepublishAfterSeconds());
        int scanned = 0;
        Map<OutboxPublisher.Result, Integer> results = new LinkedHashMap<>();
        for (Map<String, AttributeValue> pending : dynamoDb.query(QueryRequest.builder()
                .tableName(table.name())
                .indexName(QuoteKeys.GSI2)
                .keyConditionExpression("#pk = :pending AND #sk < :cutoff")
                .expressionAttributeNames(Map.of("#pk", QuoteKeys.GSI2_PK, "#sk", QuoteKeys.GSI2_SK))
                .expressionAttributeValues(
                        Map.of(":pending", s(QuoteKeys.OUTBOX_PENDING), ":cutoff", s(cutoff.toString())))
                .limit(config.reconcilerBatchSize())
                .build()).items()) {
            scanned++;
            // WHY: o índice é eventualmente consistente; a leitura da tabela confirma que o
            // item segue pendente
            Optional<Map<String, AttributeValue>> item = table.get(Attr.string(pending, QuoteKeys.PK), QuoteKeys.META);
            if (item.isEmpty() || Attr.string(item.get(), "publishedAt") != null) {
                continue;
            }
            OutboxPublisher.OutboxItem outbox = OutboxPublisher.OutboxItem.from(item.get());
            OutboxPublisher.Result result = publisher.publish(outbox);
            results.merge(result, 1, Integer::sum);
            if (result == OutboxPublisher.Result.PUBLISHED) {
                LOG.infof("reconciler republished outbox eventId=%s detailType=%s createdAt=%s", outbox.eventId(),
                        outbox.detailType(), Attr.string(item.get(), "createdAt"));
            }
        }
        Map<String, Integer> report = new LinkedHashMap<>();
        report.put("scanned", scanned);
        report.put("republished", results.getOrDefault(OutboxPublisher.Result.PUBLISHED, 0));
        report.put("transientFailures", results.getOrDefault(OutboxPublisher.Result.TRANSIENT_FAILURE, 0));
        report.put("permanentFailures", results.getOrDefault(OutboxPublisher.Result.PERMANENT_FAILURE, 0));
        return report;
    }

    /**
     * Idade, em segundos, do outbox pendente mais antigo, 0 sem pendentes. WHY:
     * medida pelo {@code createdAt}, não
     * pelo {@code gsi2sk}, que a falha permanente adia (§7); por isso lê todos os
     * pendentes, não só o primeiro.
     */
    long oldestPendingAgeSeconds(Instant now) {
        long oldest = 0;
        Map<String, AttributeValue> startKey = null;
        int pages = 0;
        do {
            QueryResponse page = dynamoDb.query(QueryRequest.builder()
                    .tableName(table.name())
                    .indexName(QuoteKeys.GSI2)
                    .keyConditionExpression("#pk = :pending")
                    .expressionAttributeNames(Map.of("#pk", QuoteKeys.GSI2_PK))
                    .expressionAttributeValues(Map.of(":pending", s(QuoteKeys.OUTBOX_PENDING)))
                    .exclusiveStartKey(startKey)
                    .build());
            for (Map<String, AttributeValue> item : page.items()) {
                Instant createdAt = Attr.instant(item, "createdAt");
                if (createdAt != null) {
                    oldest = Math.max(oldest, Duration.between(createdAt, now).toSeconds());
                }
            }
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey()
                    : null;
        } while (startKey != null && ++pages < MAX_AGE_PAGES);
        return oldest;
    }
}

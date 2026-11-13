package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.s;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mmda.serverless.quote.domain.QuoteKeys;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Republicador agendado (M1 do desenho, §9): lê o {@code OUTBOX_PENDING} mais antigo que
 * {@code outboxRepublishAfterSeconds} no índice {@code gsi2} e publica com o mesmo código da relay. É o
 * único caminho de volta de um registro que o stream descartou ou que nunca chegou à relay, como numa
 * queda entre o commit e a publicação (T19). A reconciliação completa entra no M4.
 */
@Singleton
@Named("reconciler")
public class ReconcilerHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = Logger.getLogger(ReconcilerHandler.class);

    private final DynamoDbClient dynamoDb;
    private final QuoteTable table;
    private final OutboxPublisher publisher;
    private final QuoteFunctionsConfig config;
    private final Clock clock;

    ReconcilerHandler(DynamoDbClient dynamoDb, QuoteTable table, OutboxPublisher publisher, QuoteFunctionsConfig config,
            Clock clock) {
        this.dynamoDb = dynamoDb;
        this.table = table;
        this.publisher = publisher;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Instant cutoff = Instant.now(clock).minusSeconds(config.outboxRepublishAfterSeconds());
        int scanned = 0;
        Map<OutboxPublisher.Result, Integer> results = new LinkedHashMap<>();
        for (Map<String, AttributeValue> pending : dynamoDb.query(QueryRequest.builder()
                .tableName(table.name())
                .indexName(QuoteKeys.GSI2)
                .keyConditionExpression("#pk = :pending AND #sk < :cutoff")
                .expressionAttributeNames(Map.of("#pk", QuoteKeys.GSI2_PK, "#sk", QuoteKeys.GSI2_SK))
                .expressionAttributeValues(Map.of(":pending", s(QuoteKeys.OUTBOX_PENDING), ":cutoff", s(cutoff.toString())))
                .limit(config.reconcilerBatchSize())
                .build()).items()) {
            scanned++;
            // WHY: o índice é eventualmente consistente; a leitura da tabela confirma que o item segue pendente
            Optional<Map<String, AttributeValue>> item = table.get(Attr.string(pending, QuoteKeys.PK), QuoteKeys.META);
            if (item.isEmpty() || Attr.string(item.get(), "publishedAt") != null) {
                continue;
            }
            OutboxPublisher.Result result = publisher.publish(OutboxPublisher.OutboxItem.from(item.get()));
            results.merge(result, 1, Integer::sum);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scanned", scanned);
        report.put("republished", results.getOrDefault(OutboxPublisher.Result.PUBLISHED, 0));
        report.put("transientFailures", results.getOrDefault(OutboxPublisher.Result.TRANSIENT_FAILURE, 0));
        report.put("permanentFailures", results.getOrDefault(OutboxPublisher.Result.PERMANENT_FAILURE, 0));
        if (scanned > 0) {
            LOG.infof("reconciler outbox report: %s", report);
        }
        return report;
    }
}

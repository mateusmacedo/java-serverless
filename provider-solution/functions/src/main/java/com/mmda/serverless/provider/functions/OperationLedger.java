package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.n;
import static com.mmda.serverless.provider.functions.Items.putIfPresent;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.QueryReconcileRequest;
import com.mmda.serverless.provider.domain.operations.QueryReconciled;
import com.mmda.serverless.provider.domain.operations.ReconcileStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Reconciliação manual na tabela da provider ({@code OPERATION#}, desenho, §5):
 * o pedido é deduplicado pelo
 * {@code operationId}. A revisão 1 é o {@code RUNNING}; o resultado, revisão 2,
 * sai na mesma transação do
 * {@code QueryReconciled}, então a reentrega depois do resultado não reconcilia
 * nem publica de novo.
 */
@Component
class OperationLedger {

        static final long RUNNING_REVISION = 1;
        static final long RESULT_REVISION = 2;

        private final DynamoDbClient dynamoDb;
        private final Transactions transactions;
        private final OutboxStore outbox;
        private final ProviderProperties properties;

        OperationLedger(DynamoDbClient dynamoDb, Transactions transactions, OutboxStore outbox,
                        ProviderProperties properties) {
                this.dynamoDb = dynamoDb;
                this.transactions = transactions;
                this.outbox = outbox;
                this.properties = properties;
        }

        /**
         * Grava o {@code RUNNING}, ou encontra o da entrega anterior. Vazio quando a
         * operação já tem resultado: a
         * reentrega não faz nada.
         */
        Optional<ReconcileStatus> start(QueryReconcileRequest request, Instant now) {
                Map<String, AttributeValue> item = Items.item(Items.operationPk(request.operationId()), META,
                                Items.TYPE_OPERATION);
                item.put("operationId", s(request.operationId()));
                item.put("organizationId", s(request.organizationId()));
                item.put("queryId", s(request.queryId()));
                putIfPresent(item, "reason", request.reason());
                putIfPresent(item, "requestedBy", request.requestedBy());
                item.put("requestEventId", s(request.eventId()));
                item.put("status", s(ReconcileStatus.RUNNING.name()));
                item.put("safeSummary", s("Reconciliação em andamento"));
                item.put("revision", n(RUNNING_REVISION));
                item.put("createdAt", s(Timestamps.format(now)));
                item.put("updatedAt", s(Timestamps.format(now)));
                Transactions.Result result = transactions.write(List.of(TransactWriteItem.builder()
                                .put(put -> put
                                                .tableName(properties.tableName())
                                                .item(item)
                                                .conditionExpression("attribute_not_exists(pk)"))
                                .build()));
                if (result.committed()) {
                        return Optional.of(ReconcileStatus.RUNNING);
                }
                ReconcileStatus status = status(request.operationId()).orElse(ReconcileStatus.RUNNING);
                return status == ReconcileStatus.RUNNING ? Optional.of(status) : Optional.empty();
        }

        /**
         * Resultado e {@code QueryReconciled} numa transação; {@code false} se outra
         * entrega já gravou o resultado.
         */
        boolean finish(QueryReconcileRequest request, ReconcileStatus status, String safeSummary, Instant now) {
                QueryReconciled event = QueryReconciled.of(request, status, safeSummary, RESULT_REVISION,
                                Timestamps.format(now));
                TransactWriteItem operation = TransactWriteItem.builder()
                                .update(update -> update
                                                .tableName(properties.tableName())
                                                .key(Items.key(Items.operationPk(request.operationId()), META))
                                                .updateExpression(
                                                                "SET #status = :status, safeSummary = :summary, revision = :revision, "
                                                                                + "updatedAt = :now")
                                                .conditionExpression("#status = :running")
                                                .expressionAttributeNames(Map.of("#status", "status"))
                                                .expressionAttributeValues(Map.of(
                                                                ":status", s(status.name()),
                                                                ":summary", s(safeSummary),
                                                                ":revision", n(RESULT_REVISION),
                                                                ":now", s(Timestamps.format(now)),
                                                                ":running", s(ReconcileStatus.RUNNING.name()))))
                                .build();
                return transactions.write(List.of(operation,
                                outbox.put(new Settlement.OutboxEvent(event.eventId(), QueryReconciled.DETAIL_TYPE,
                                                event), now)))
                                .committed();
        }

        Optional<ReconcileStatus> status(String operationId) {
                return Items.string(dynamoDb.getItem(builder -> builder
                                .tableName(properties.tableName())
                                .consistentRead(true)
                                .key(Items.key(Items.operationPk(operationId), META)))
                                .item(), "status").map(ReconcileStatus::valueOf);
        }
}

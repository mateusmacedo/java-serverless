package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.s;

import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Reconciliation;
import com.mmda.serverless.quote.domain.WorkRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;
import software.amazon.awssdk.services.sfn.model.HistoryEventType;

/**
 * Waiters que o {@code reconciler} acompanha (§8 e §9 do desenho): retoma os parados em {@code RESUMING} com o
 * {@code resumePayload} já gravado (T13), sem pedido novo à provider, e confere no histórico da execução os
 * {@code EXPIRED} com resultado gravado antes de abrir {@code TASK_EXPIRED}.
 */
@ApplicationScoped
public class WaiterReconciler {

    private static final Logger LOG = Logger.getLogger(WaiterReconciler.class);
    private static final String WAITER_PREFIX = "WAITER#";
    private static final int HISTORY_PAGE_SIZE = 1000;
    private static final Set<HistoryEventType> ENDED_EXECUTION = EnumSet.of(HistoryEventType.EXECUTION_SUCCEEDED,
            HistoryEventType.EXECUTION_FAILED, HistoryEventType.EXECUTION_ABORTED, HistoryEventType.EXECUTION_TIMED_OUT);

    private final DynamoDbClient dynamoDb;
    private final QuoteTable table;
    private final ResultResumer resumer;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final QuoteFunctionsConfig config;
    private final ReconcilerConfig reconcilerConfig;

    WaiterReconciler(DynamoDbClient dynamoDb, QuoteTable table, ResultResumer resumer, SfnClient sfn,
            ObjectMapper objectMapper, QuoteFunctionsConfig config, ReconcilerConfig reconcilerConfig) {
        this.dynamoDb = dynamoDb;
        this.table = table;
        this.resumer = resumer;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.config = config;
        this.reconcilerConfig = reconcilerConfig;
    }

    /**
     * Retoma o waiter {@code RESUMING} mais antigo que {@code resumingRetryAfterSeconds}: o {@code resume} gravou
     * o resultado e caiu antes do {@code SendTaskSuccess}, ou a mensagem foi para a DLQ. Devolve quantos
     * retomou. A falha de um waiter fica no log e não impede os seguintes.
     */
    int resumeStale(Instant now) {
        Instant cutoff = now.minusSeconds(reconcilerConfig.resumingRetryAfterSeconds());
        int resumed = 0;
        for (Map<String, AttributeValue> indexed : indexed(QuoteKeys.WAITER_RESUMING, cutoff)) {
            WorkRef workRef = workRefOf(indexed);
            try {
                if (resume(workRef, now)) {
                    resumed++;
                }
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "reconciler could not resume workRef=%s; it stays for the next cycle", workRef);
            }
        }
        return resumed;
    }

    private boolean resume(WorkRef workRef, Instant now) {
        Optional<Map<String, AttributeValue>> waiter = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META);
        if (waiter.isEmpty() || !"RESUMING".equals(Attr.string(waiter.get(), "status"))) {
            return false;
        }
        String finalStatus = resumer.resumeExecution(workRef);
        table.refreshSummary(Attr.string(waiter.get(), "quoteId"), (int) Attr.number(waiter.get(), "version", 0), now);
        LOG.infof("reconciler resumed workRef=%s with the stored result: %s", workRef, finalStatus);
        return true;
    }

    /**
     * Confere os waiters {@code EXPIRED} ainda no índice {@code WAITER_EXPIRED} (§3). Sem resultado gravado, é o
     * timeout normal da etapa e o waiter só sai do índice. Com resultado gravado, o histórico da execução diz se
     * ele chegou a ela; se a execução encerrou sem recebê-lo, abre {@code TASK_EXPIRED} na mesma transação que
     * tira o waiter do índice. Devolve quantas ocorrências abriu.
     */
    int reviewExpired(Instant now) {
        int opened = 0;
        for (Map<String, AttributeValue> indexed : indexed(QuoteKeys.WAITER_EXPIRED, now.plusSeconds(1))) {
            WorkRef workRef = workRefOf(indexed);
            try {
                if (review(workRef, now)) {
                    opened++;
                }
            } catch (RuntimeException failure) {
                LOG.errorf(failure, "reconciler could not review the expired workRef=%s", workRef);
            }
        }
        return opened;
    }

    /** {@code true} quando abriu {@code TASK_EXPIRED}. */
    private boolean review(WorkRef workRef, Instant now) {
        Optional<Map<String, AttributeValue>> found = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META);
        if (found.isEmpty() || !"EXPIRED".equals(Attr.string(found.get(), "status"))) {
            return false;
        }
        Map<String, AttributeValue> waiter = found.get();
        String resumePayload = Attr.string(waiter, "resumePayload");
        boolean recorded = resumePayload != null || recordedResultEventId(workRef, waiter) != null;
        Reconciliation.ExpiredTaskReview review = recorded
                ? history(Attr.string(waiter, "executionArn"), resumePayload)
                : Reconciliation.expiredTask(false, false, false);
        if (review == Reconciliation.ExpiredTaskReview.WAIT) {
            return false;
        }
        boolean open = review == Reconciliation.ExpiredTaskReview.OPEN_TASK_EXPIRED;
        try {
            table.transact(open ? List.of(table.issueWrite(taskExpired(workRef, waiter), now), leaveIndex(workRef))
                    : List.of(leaveIndex(workRef)));
        } catch (TransactionCanceledException changed) {
            if (!QuoteTable.isConditionFailure(changed)) {
                throw changed;
            }
            return false;
        }
        if (open) {
            LOG.warnf("TASK_EXPIRED opened for workRef=%s quoteId=%s", workRef, Attr.string(waiter, "quoteId"));
        }
        return open;
    }

    /**
     * Lê o histórico da execução do waiter. Sem {@code executionArn}, ou com a execução apagada, não há como provar
     * a entrega, e a execução conta como encerrada.
     */
    private Reconciliation.ExpiredTaskReview history(String executionArn, String resumePayload) {
        if (executionArn == null) {
            return Reconciliation.expiredTask(true, false, true);
        }
        JsonNode expected = resumePayload == null ? null : readTree(resumePayload);
        boolean delivered = false;
        boolean ended = false;
        try {
            for (HistoryEvent event : sfn.getExecutionHistoryPaginator(builder -> builder
                    .executionArn(executionArn).maxResults(HISTORY_PAGE_SIZE)).events()) {
                if (expected != null && event.type() == HistoryEventType.TASK_SUCCEEDED
                        && event.taskSucceededEventDetails() != null) {
                    delivered |= carries(readTree(event.taskSucceededEventDetails().output()), expected);
                }
                ended |= ENDED_EXECUTION.contains(event.type());
            }
        } catch (ExecutionDoesNotExistException missing) {
            ended = true;
        }
        return Reconciliation.expiredTask(true, delivered, ended);
    }

    /**
     * A saída de um {@code TaskSucceeded} carrega o resultado gravado: é o {@code resumePayload} entregue pelo
     * {@code SendTaskSuccess} ou o {@code Payload} do passo de timeout que usou o resultado já gravado (§8). Na
     * seguradora, bastam a seguradora e a situação, porque o passo de timeout devolve só esses campos.
     */
    static boolean carries(JsonNode output, JsonNode expected) {
        return matches(output, expected) || matches(output.path("Payload"), expected);
    }

    private static boolean matches(JsonNode candidate, JsonNode expected) {
        if (!candidate.isObject() || !expected.isObject()) {
            return false;
        }
        if (expected.hasNonNull("insurerId")) {
            return expected.get("insurerId").equals(candidate.get("insurerId"))
                    && expected.path("status").equals(candidate.path("status"));
        }
        return withoutNulls(candidate).equals(withoutNulls(expected));
    }

    private static JsonNode withoutNulls(JsonNode node) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        List<String> nulls = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : copy.properties()) {
            if (field.getValue().isNull()) {
                nulls.add(field.getKey());
            }
        }
        copy.remove(nulls);
        return copy;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null ? "" : json);
        } catch (IOException unreadable) {
            return objectMapper.missingNode();
        }
    }

    /** {@code resultEventId} gravado com o resultado: no {@code RESULT} da seguradora ou na evidência usada. */
    private String recordedResultEventId(WorkRef workRef, Map<String, AttributeValue> waiter) {
        String quoteId = Attr.string(waiter, "quoteId");
        int version = (int) Attr.number(waiter, "version", 0);
        if (quoteId == null || version < 1) {
            return null;
        }
        String sk = WorkRef.Kind.INSURER.name().equals(Attr.string(waiter, "kind"))
                ? QuoteKeys.resultSk(version, Attr.string(waiter, "insurerId"))
                : QuoteKeys.evidenceSk(version, workRef);
        return table.get(QuoteKeys.quotePk(quoteId), sk).map(item -> Attr.string(item, "resultEventId")).orElse(null);
    }

    private QuoteIssue taskExpired(WorkRef workRef, Map<String, AttributeValue> waiter) {
        String quoteId = Attr.string(waiter, "quoteId");
        int version = (int) Attr.number(waiter, "version", 0);
        String insurerId = Attr.string(waiter, "insurerId");
        String summary = insurerId != null
                ? "O resultado gravado da seguradora " + insurerId + " na versão " + version
                        + " não chegou à execução, que já terminou."
                : "O resultado gravado do enriquecimento na versão " + version
                        + " não chegou à execução, que já terminou.";
        return new QuoteIssue(QuoteIssues.Type.TASK_EXPIRED, workRef.value(),
                quoteId == null ? null : table.organizationOf(quoteId), quoteId, version < 1 ? null : version, null,
                summary);
    }

    /** Tira o waiter do índice só se ele continua {@code EXPIRED} e indexado. */
    private TransactWriteItem leaveIndex(WorkRef workRef) {
        return table.update(QuoteKeys.waiterPk(workRef), QuoteKeys.META,
                "REMOVE " + QuoteKeys.GSI2_PK + ", " + QuoteKeys.GSI2_SK,
                "#status = :expired AND " + QuoteKeys.GSI2_PK + " = :index", Map.of("#status", "status"),
                Map.of(":expired", s("EXPIRED"), ":index", s(QuoteKeys.WAITER_EXPIRED)));
    }

    private List<Map<String, AttributeValue>> indexed(String index, Instant before) {
        return dynamoDb.query(QueryRequest.builder()
                .tableName(table.name())
                .indexName(QuoteKeys.GSI2)
                .keyConditionExpression("#pk = :index AND #sk < :before")
                .expressionAttributeNames(Map.of("#pk", QuoteKeys.GSI2_PK, "#sk", QuoteKeys.GSI2_SK))
                .expressionAttributeValues(Map.of(":index", s(index), ":before", s(before.toString())))
                .limit(config.reconcilerBatchSize())
                .build()).items();
    }

    private static WorkRef workRefOf(Map<String, AttributeValue> waiter) {
        return new WorkRef(Attr.string(waiter, QuoteKeys.PK).substring(WAITER_PREFIX.length()));
    }
}

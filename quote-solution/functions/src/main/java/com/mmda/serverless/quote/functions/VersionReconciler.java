package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.s;

import com.mmda.serverless.quote.domain.Consolidation.ConsolidationResult;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Reconciliation;
import com.mmda.serverless.quote.domain.Reconciliation.ExecutionState;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;

/**
 * Versões {@code ACTIVE_VERSION} (§9 do desenho): finaliza a que tem a execução encerrada em {@code FAILED},
 * {@code TIMED_OUT} ou {@code ABORTED} (T20), abre {@code STUCK_VERSION} para a que passou do prazo com a margem
 * e repara o resumo do {@code META} da versão atual.
 */
@ApplicationScoped
public class VersionReconciler {

    private static final Logger LOG = Logger.getLogger(VersionReconciler.class);

    private final DynamoDbClient dynamoDb;
    private final QuoteTable table;
    private final WorkflowSteps steps;
    private final OutboxPublisher publisher;
    private final SfnClient sfn;
    private final QuoteFunctionsConfig config;
    private final ReconcilerConfig reconcilerConfig;

    VersionReconciler(DynamoDbClient dynamoDb, QuoteTable table, WorkflowSteps steps, OutboxPublisher publisher,
            SfnClient sfn, QuoteFunctionsConfig config, ReconcilerConfig reconcilerConfig) {
        this.dynamoDb = dynamoDb;
        this.table = table;
        this.steps = steps;
        this.publisher = publisher;
        this.sfn = sfn;
        this.config = config;
        this.reconcilerConfig = reconcilerConfig;
    }

    /**
     * Contadores da varredura: {@code active}, {@code finalized}, {@code stuck} e {@code summariesRepaired}. Além das
     * versões {@code ACTIVE_VERSION}, confere o resumo das {@code TERMINAL} ainda em {@code SUMMARY_PENDING}, cujo
     * {@code refreshSummary} falhou depois da consolidação. A falha de uma versão fica no log e não impede as outras.
     */
    Map<String, Integer> reconcile(Instant now) {
        Duration stuckMargin = Duration.ofSeconds(reconcilerConfig.stuckVersionMarginSeconds());
        Map<String, Integer> report = new LinkedHashMap<>(Map.of("active", 0, "finalized", 0, "stuck", 0,
                "summariesRepaired", 0));
        for (Map<String, AttributeValue> indexed : indexed(QuoteKeys.ACTIVE_VERSION)) {
            String quoteId = Attr.string(indexed, "quoteId");
            int version = (int) Attr.number(indexed, "version", 0);
            guarded(quoteId, version, () -> reconcileVersion(quoteId, version, stuckMargin, now, report));
        }
        for (Map<String, AttributeValue> indexed : indexed(QuoteKeys.SUMMARY_PENDING)) {
            String quoteId = Attr.string(indexed, "quoteId");
            int version = (int) Attr.number(indexed, "version", 0);
            guarded(quoteId, version, () -> confirmSummary(quoteId, version, now, report));
        }
        return report;
    }

    private static void guarded(String quoteId, int version, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            LOG.errorf(failure, "reconciler could not check quoteId=%s version=%d", quoteId, version);
        }
    }

    private List<Map<String, AttributeValue>> indexed(String index) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        int remaining = config.reconcilerBatchSize();
        do {
            QueryResponse page = dynamoDb.query(QueryRequest.builder()
                    .tableName(table.name())
                    .indexName(QuoteKeys.GSI2)
                    .keyConditionExpression("#pk = :index")
                    .expressionAttributeNames(Map.of("#pk", QuoteKeys.GSI2_PK))
                    .expressionAttributeValues(Map.of(":index", s(index)))
                    .limit(remaining)
                    .exclusiveStartKey(startKey)
                    .build());
            items.addAll(page.items());
            remaining -= page.items().size();
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null && remaining > 0);
        return items;
    }

    /** Repara o {@code META} da versão consolidada, se diverge, e a tira do índice {@code SUMMARY_PENDING}. */
    private void confirmSummary(String quoteId, int version, Instant now, Map<String, Integer> report) {
        if (quoteId == null || version < 1) {
            return;
        }
        if (table.repairSummary(quoteId, version, now)) {
            report.merge("summariesRepaired", 1, Integer::sum);
            LOG.warnf("summary of the consolidated quoteId=%s version=%d repaired", quoteId, version);
        }
        table.clearSummaryPending(quoteId, version);
    }

    private void reconcileVersion(String quoteId, int version, Duration stuckMargin, Instant now,
            Map<String, Integer> report) {
        if (quoteId == null || version < 1) {
            return;
        }
        // WHY: o índice é eventualmente consistente; a leitura da tabela confirma que a versão segue ativa
        Optional<Map<String, AttributeValue>> found = table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version));
        if (found.isEmpty() || Phase.TERMINAL.name().equals(Attr.string(found.get(), "phase"))) {
            return;
        }
        report.merge("active", 1, Integer::sum);
        Map<String, AttributeValue> versionItem = found.get();
        ExecutionState state = executionState(versionItem, quoteId, version);
        switch (Reconciliation.versionAction(state, Attr.instant(versionItem, "deadlineAt"), stuckMargin, now)) {
            case FINALIZE -> {
                QuoteIssue issue = new QuoteIssue(QuoteIssues.Type.WORKFLOW_FAILED,
                        QuoteIssues.versionTarget(quoteId, version), table.organizationOf(quoteId), quoteId, version,
                        null, "A execução da versão " + version + " terminou em " + state.name()
                                + "; a reconciliação finalizou a versão com os resultados já gravados.");
                Optional<ConsolidationResult> finalized = steps.finalizeEndedExecution(quoteId, version, state, issue);
                if (finalized.isPresent()) {
                    report.merge("finalized", 1, Integer::sum);
                    LOG.warnf("WORKFLOW_FAILED: quoteId=%s version=%d execution %s finalized as %s", quoteId, version,
                            state, finalized.get().outcome());
                }
            }
            case STUCK -> {
                table.writeIssue(new QuoteIssue(QuoteIssues.Type.STUCK_VERSION, QuoteIssues.versionTarget(quoteId, version),
                        table.organizationOf(quoteId), quoteId, version, null,
                        "A versão " + version + " passou do prazo sem terminar; a execução está " + state.name() + "."),
                        now);
                report.merge("stuck", 1, Integer::sum);
                LOG.warnf("STUCK_VERSION: quoteId=%s version=%d execution %s", quoteId, version, state);
            }
            case NONE -> {
            }
        }
        if (table.repairSummary(quoteId, version, now)) {
            report.merge("summariesRepaired", 1, Integer::sum);
        }
    }

    /** {@code DescribeExecution} pelo {@code executionArn} gravado ou, sem ele, pelo derivado do nome (§7). */
    private ExecutionState executionState(Map<String, AttributeValue> versionItem, String quoteId, int version) {
        String executionArn = Attr.string(versionItem, "executionArn");
        if (executionArn == null) {
            String name = Attr.string(versionItem, "executionName");
            executionArn = publisher.executionArn(name != null ? name : QuoteKeys.executionName(quoteId, version));
        }
        String arn = executionArn;
        try {
            return ExecutionState.of(sfn.describeExecution(builder -> builder.executionArn(arn)).statusAsString());
        } catch (ExecutionDoesNotExistException missing) {
            return ExecutionState.MISSING;
        }
    }
}

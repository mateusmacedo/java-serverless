package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Deadlines;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import com.mmda.serverless.quote.domain.WorkRef;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.InvalidTokenException;
import software.amazon.awssdk.services.sfn.model.TaskTimedOutException;

/**
 * Tarefa {@code waitForTaskToken} da máquina (§8 do desenho): grava o waiter
 * com o token cifrado e o
 * outbox do pedido numa transação. É idempotente: a nova tentativa da tarefa só
 * troca o token, e um
 * resultado que já chegou é entregue com {@code SendTaskSuccess} no token novo.
 * O token nunca sai da
 * quote-solution: o pedido leva só o {@code workRef}.
 */
@Singleton
@Named("dispatch")
public class DispatchHandler implements RequestHandler<DispatchHandler.DispatchRequest, Map<String, Object>> {

    private static final Logger LOG = Logger.getLogger(DispatchHandler.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DispatchRequest(WorkRef.Kind kind, String quoteId, int version, String insurerId,
            String executionArn, String taskToken, int timeoutSeconds) {
    }

    private final QuoteTable table;
    private final TokenCipher cipher;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    DispatchHandler(QuoteTable table, TokenCipher cipher, SfnClient sfn, ObjectMapper objectMapper, Clock clock) {
        this.table = table;
        this.cipher = cipher;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> handleRequest(DispatchRequest request, Context context) {
        if (request.timeoutSeconds() <= 0 || request.taskToken() == null) {
            throw new IllegalArgumentException("dispatch needs the task token and the step timeoutSeconds");
        }
        WorkRef workRef = WorkRef.of(request.quoteId(), request.version(), request.kind(), request.insurerId());
        Optional<Map<String, AttributeValue>> waiter = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META);
        if (waiter.isEmpty()) {
            try {
                firstDispatch(request, workRef);
                return output(workRef, "WAITING");
            } catch (TransactionCanceledException concurrent) {
                waiter = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META);
                if (waiter.isEmpty()) {
                    throw concurrent;
                }
            }
        }
        return redispatch(request, workRef, waiter.get());
    }

    private void firstDispatch(DispatchRequest request, WorkRef workRef) {
        String quoteId = request.quoteId();
        int version = request.version();
        Map<String, AttributeValue> versionItem = table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version))
                .orElseThrow(() -> new IllegalStateException("quote " + quoteId + " has no version " + version));
        String organizationId = table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.META)
                .map(meta -> Attr.string(meta, "organizationId"))
                .orElseThrow(() -> new IllegalStateException("quote " + quoteId + " has no META"));
        QuoteSnapshot snapshot = read(Attr.string(versionItem, "snapshot"), QuoteSnapshot.class);
        Instant now = Instant.now(clock);
        String versionDeadline = Attr.string(versionItem, "deadlineAt");
        Instant deadlineAt = Deadlines.requestDeadline(now, Duration.ofSeconds(request.timeoutSeconds()),
                versionDeadline == null ? null : Instant.parse(versionDeadline));
        Map<String, Object> detail = request.kind() == WorkRef.Kind.ENRICHMENT
                ? QuoteEvents.enrichmentRequested(now, workRef, organizationId, quoteId, version, snapshot, deadlineAt)
                : QuoteEvents.insurerQuoteRequested(now, workRef, organizationId, quoteId, version,
                        request.insurerId(), snapshot, enrichmentSummary(quoteId, version), deadlineAt);
        String detailType = request.kind() == WorkRef.Kind.ENRICHMENT
                ? QuoteEvents.ENRICHMENT_REQUESTED
                : QuoteEvents.INSURER_QUOTE_REQUESTED;

        Map<String, AttributeValue> waiter = new HashMap<>();
        waiter.put(QuoteKeys.PK, s(QuoteKeys.waiterPk(workRef)));
        waiter.put(QuoteKeys.SK, s(QuoteKeys.META));
        waiter.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_WAITER));
        waiter.put("workRef", s(workRef.value()));
        waiter.put("status", s("WAITING"));
        waiter.put("tokenCiphertext", s(cipher.encrypt(request.taskToken(), workRef)));
        waiter.put("quoteId", s(quoteId));
        waiter.put("version", n(version));
        waiter.put("kind", s(request.kind().name()));
        if (request.insurerId() != null) {
            waiter.put("insurerId", s(request.insurerId()));
        }
        waiter.put("executionArn", s(request.executionArn()));
        waiter.put("expiresAt", s(deadlineAt.toString()));
        waiter.put("createdAt", s(now.toString()));
        waiter.put("updatedAt", s(now.toString()));

        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(table.put(waiter, QuoteTable.NOT_EXISTS));
        writes.add(table.put(table.outboxItem(workRef.requestEventId(), detailType, detail, quoteId, version, now),
                QuoteTable.NOT_EXISTS));
        if (request.kind() == WorkRef.Kind.INSURER) {
            writes.add(table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.resultSk(version, request.insurerId()),
                    "SET #status = :processing, revision = revision + :one, updatedAt = :now",
                    "#status = :pending", Map.of("#status", "status"),
                    Map.of(":processing", s(InsurerStatus.PROCESSING.name()), ":pending",
                            s(InsurerStatus.PENDING.name()),
                            ":one", n(1), ":now", s(now.toString()))));
        }
        table.transact(writes);
        if (request.kind() == WorkRef.Kind.INSURER) {
            table.refreshSummary(quoteId, version, now);
        }
        LOG.infof("dispatched %s workRef=%s quoteId=%s version=%d", detailType, workRef, quoteId, version);
    }

    private Map<String, Object> redispatch(DispatchRequest request, WorkRef workRef,
            Map<String, AttributeValue> waiter) {
        String resumePayload = Attr.string(waiter, "resumePayload");
        if (resumePayload == null && "WAITING".equals(Attr.string(waiter, "status"))) {
            Instant now = Instant.now(clock);
            boolean replaced = table.updateIf(table.update(QuoteKeys.waiterPk(workRef), QuoteKeys.META)
                    .updateExpression("SET tokenCiphertext = :token, executionArn = :arn, updatedAt = :now")
                    .conditionExpression("#status = :waiting")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":token", s(cipher.encrypt(request.taskToken(), workRef)),
                            ":arn", s(request.executionArn()), ":now", s(now.toString()), ":waiting", s("WAITING")))
                    .build());
            if (replaced) {
                return output(workRef, "WAITING");
            }
            resumePayload = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META)
                    .map(current -> Attr.string(current, "resumePayload"))
                    .orElse(null);
        }
        if (resumePayload == null) {
            return output(workRef, Attr.string(waiter, "status"));
        }
        String output = resumePayload;
        try {
            sfn.sendTaskSuccess(builder -> builder.taskToken(request.taskToken()).output(output));
        } catch (TaskTimedOutException | InvalidTokenException expired) {
            LOG.warnf("result of workRef=%s could not resume the new token: %s", workRef, expired.getMessage());
        }
        return output(workRef, "RESUMED");
    }

    /**
     * Resumo do enriquecimento enviado à seguradora: a evidência usada, sem
     * resposta bruta.
     */
    private Map<String, Object> enrichmentSummary(String quoteId, int version) {
        WorkRef enrichment = WorkRef.of(quoteId, version, WorkRef.Kind.ENRICHMENT, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.evidenceSk(version, enrichment)).ifPresent(evidence -> {
            summary.put("evidenceId", Attr.string(evidence, "evidenceId"));
            summary.put("queryId", Attr.string(evidence, "queryId"));
            summary.put("reuseDecision", Attr.string(evidence, "reuseDecision"));
            summary.put("resultType", Attr.string(evidence, "resultType"));
            JsonNode result = read(Attr.string(evidence, "summary"), JsonNode.class).get("result");
            summary.put("result", result);
        });
        return summary;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> output(WorkRef workRef, String status) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workRef", workRef.value());
        output.put("status", status);
        return output;
    }
}

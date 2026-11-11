package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.EnrichmentOutcome;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.Insurers;
import com.mmda.serverless.quote.domain.MissingField;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.WorkRef;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.InvalidTokenException;
import software.amazon.awssdk.services.sfn.model.TaskTimedOutException;

/**
 * Retomada (§8 do desenho): grava o resultado da provider e a intenção de retomada numa transação, com o
 * {@code CONSUMED#<eventId>}, e só depois chama {@code SendTaskSuccess}. Resultado depois do prazo ou de
 * outro resultado vira evidência tardia, sem reabrir a versão (RN10).
 */
@ApplicationScoped
public class ResultResumer {

    private static final Logger LOG = Logger.getLogger(ResultResumer.class);
    private static final Set<InsurerStatus> REPORTED_STATUSES = Set.of(InsurerStatus.QUOTED, InsurerStatus.DECLINED,
            InsurerStatus.NEEDS_INFORMATION, InsurerStatus.UNAVAILABLE);

    /** O que aconteceu com o evento. */
    public enum Disposition {
        RESUMED,
        DISCARDED,
        LATE,
        DUPLICATE,
        UNKNOWN_WORK,
        IGNORED
    }

    private final QuoteTable table;
    private final TokenCipher cipher;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ResultResumer(QuoteTable table, TokenCipher cipher, SfnClient sfn, ObjectMapper objectMapper, Clock clock) {
        this.table = table;
        this.cipher = cipher;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Disposition handle(String body) {
        JsonNode envelope = readTree(body);
        String detailType = envelope.path("detail-type").asText();
        if (!QuoteEvents.ENRICHMENT_RESOLVED.equals(detailType) && !QuoteEvents.INSURER_RESULT_RECORDED.equals(detailType)) {
            LOG.warnf("ignoring unexpected event %s", detailType);
            return Disposition.IGNORED;
        }
        JsonNode detail = envelope.path("detail");
        String eventId = required(detail, "eventId");
        WorkRef workRef = new WorkRef(required(detail, "workRef"));
        Optional<Map<String, AttributeValue>> found = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META);
        if (found.isEmpty()) {
            // WHY: o pedido só sai depois do commit do waiter; um workRef desconhecido não é desta quote (M4 registra)
            LOG.warnf("result %s for unknown workRef=%s", eventId, workRef);
            return Disposition.UNKNOWN_WORK;
        }
        Waiter waiter = Waiter.of(found.get());
        Instant now = Instant.now(clock);
        if (waiter.expiresAt() != null && !now.isBefore(waiter.expiresAt())) {
            return recordLate(waiter, detail, eventId, "LATE", now);
        }
        Result result = waiter.kind() == WorkRef.Kind.INSURER
                ? insurerResult(waiter, workRef, detail, now)
                : enrichmentResult(waiter, workRef, detail, now);
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(table.update(QuoteKeys.waiterPk(workRef), QuoteKeys.META,
                "SET #status = :resuming, resultEventId = :eventId, resumePayload = :payload, "
                        + QuoteKeys.GSI2_PK + " = :gsi2pk, " + QuoteKeys.GSI2_SK + " = :now, updatedAt = :now",
                "#status = :waiting", Map.of("#status", "status"),
                Map.of(":resuming", s("RESUMING"), ":eventId", s(eventId), ":payload", s(table.toJson(result.payload())),
                        ":gsi2pk", s(QuoteKeys.WAITER_RESUMING), ":now", s(now.toString()), ":waiting", s("WAITING"))));
        writes.addAll(result.writes());
        writes.add(table.put(consumed(eventId, now), QuoteTable.NOT_EXISTS));
        try {
            table.transact(writes);
        } catch (TransactionCanceledException canceled) {
            if (!QuoteTable.isConditionFailure(canceled)) {
                throw canceled;
            }
            Waiter current = Waiter.of(table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META).orElseThrow());
            boolean redelivered = "RESUMING".equals(current.status()) && eventId.equals(current.resultEventId());
            if (!redelivered) {
                if (table.get(QuoteKeys.consumedPk(eventId), QuoteKeys.META).isPresent()) {
                    return Disposition.DISCARDED;
                }
                String reason = current.resultEventId() != null ? "DUPLICATE" : "LATE";
                return recordLate(current, detail, eventId, reason, now);
            }
        }
        resumeExecution(workRef);
        table.refreshSummary(waiter.quoteId(), waiter.version(), Instant.now(clock));
        return Disposition.RESUMED;
    }

    /**
     * {@code SendTaskSuccess} com o token mais recente do waiter. {@code TaskTimedOut} e {@code InvalidToken}
     * marcam {@code EXPIRED} só a partir de {@code RESUMING}, para nunca rebaixar um {@code DONE}.
     */
    private void resumeExecution(WorkRef workRef) {
        Map<String, AttributeValue> waiter = table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META).orElseThrow();
        String token = cipher.decrypt(Attr.string(waiter, "tokenCiphertext"), workRef);
        String finalStatus;
        try {
            sfn.sendTaskSuccess(builder -> builder.taskToken(token).output(Attr.string(waiter, "resumePayload")));
            finalStatus = "DONE";
        } catch (TaskTimedOutException | InvalidTokenException expired) {
            LOG.warnf("task of workRef=%s already closed: %s", workRef, expired.getMessage());
            finalStatus = "EXPIRED";
        }
        table.updateIf(table.update(QuoteKeys.waiterPk(workRef), QuoteKeys.META)
                .updateExpression("SET #status = :final, updatedAt = :now REMOVE "
                        + QuoteKeys.GSI2_PK + ", " + QuoteKeys.GSI2_SK)
                .conditionExpression("#status = :resuming")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":final", s(finalStatus), ":resuming", s("RESUMING"),
                        ":now", s(Instant.now(clock).toString())))
                .build());
    }

    private Result insurerResult(Waiter waiter, WorkRef workRef, JsonNode detail, Instant now) {
        InsurerStatus status = InsurerStatus.valueOf(required(detail, "status"));
        if (!REPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("insurer result with status " + status);
        }
        String reasonCode = optional(detail, "reasonCode");
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":status", s(status.name()),
                ":missing", s(table.toJson(missingFields(detail))),
                ":one", n(1),
                ":now", s(now.toString()),
                ":pending", s(InsurerStatus.PENDING.name()),
                ":processing", s(InsurerStatus.PROCESSING.name())));
        StringBuilder expression = new StringBuilder(
                "SET #status = :status, missingFields = :missing, revision = revision + :one, updatedAt = :now");
        putIfPresent(values, expression, "reasonCode", reasonCode);
        putIfPresent(values, expression, "queryRef", optional(detail, "queryId"));
        putIfPresent(values, expression, "attemptRef", optional(detail, "attemptRef"));
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(table.update(QuoteKeys.quotePk(waiter.quoteId()), QuoteKeys.resultSk(waiter.version(), waiter.insurerId()),
                expression.toString(), "#status IN (:pending, :processing)", Map.of("#status", "status"), values));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("insurerId", waiter.insurerId());
        payload.put("status", status.name());
        payload.put("reasonCode", reasonCode);
        if (status == InsurerStatus.QUOTED) {
            JsonNode offer = detail.get("offer");
            if (offer == null || !offer.isObject()) {
                throw new IllegalArgumentException("QUOTED result without offer");
            }
            String offerId = QuoteKeys.offerId(workRef);
            writes.add(table.put(offerItem(waiter, offerId, optional(detail, "queryId"), offer, now), QuoteTable.NOT_EXISTS));
            payload.put("offerId", offerId);
        }
        return new Result(writes, payload);
    }

    private Map<String, AttributeValue> offerItem(Waiter waiter, String offerId, String queryId, JsonNode offer,
            Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(QuoteKeys.PK, s(QuoteKeys.quotePk(waiter.quoteId())));
        item.put(QuoteKeys.SK, s(QuoteKeys.offerSk(waiter.version(), offerId)));
        item.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_OFFER));
        item.put("offerId", s(offerId));
        item.put("insurerId", s(waiter.insurerId()));
        String insurerName = optional(offer, "insurerName");
        item.put("insurerName", s(insurerName != null ? insurerName
                : Insurers.nameOf(waiter.insurerId()).orElse(waiter.insurerId())));
        item.put("premiumTotal", s(required(offer, "premiumTotal")));
        item.put("currency", s(required(offer, "currency")));
        item.put("validUntil", s(Instant.parse(required(offer, "validUntil")).toString()));
        item.put("installments", s(json(offer.path("installments"))));
        item.put("coverages", s(json(offer.path("coverages"))));
        item.put("deductibles", s(json(offer.path("deductibles"))));
        item.put("createdAt", s(now.toString()));
        putIfPresent(item, "externalOfferId", optional(offer, "externalOfferId"));
        putIfPresent(item, "rawResponseRef", optional(offer, "rawResponseRef"));
        putIfPresent(item, "queryId", queryId);
        return item;
    }

    private Result enrichmentResult(Waiter waiter, WorkRef workRef, JsonNode detail, Instant now) {
        EnrichmentOutcome.ResultType resultType = EnrichmentOutcome.ResultType.valueOf(required(detail, "resultType"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("result", detail.get("result"));
        summary.put("notFoundRefs", detail.get("notFoundRefs"));
        summary.put("reusableUntil", optional(detail, "reusableUntil"));
        summary.put("errorClass", optional(detail, "errorClass"));
        Map<String, AttributeValue> evidence = new HashMap<>();
        evidence.put(QuoteKeys.PK, s(QuoteKeys.quotePk(waiter.quoteId())));
        evidence.put(QuoteKeys.SK, s(QuoteKeys.evidenceSk(waiter.version(), workRef)));
        evidence.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_EVIDENCE));
        evidence.put("resultType", s(resultType.name()));
        evidence.put("summary", s(table.toJson(summary)));
        evidence.put("receivedAt", s(now.toString()));
        putIfPresent(evidence, "evidenceId", optional(detail, "evidenceId"));
        putIfPresent(evidence, "queryId", optional(detail, "queryId"));
        putIfPresent(evidence, "reuseDecision", optional(detail, "reuseDecision"));
        putIfPresent(evidence, "policyVersion", optional(detail, "policyVersion"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultType", resultType.name());
        payload.put("evidenceId", optional(detail, "evidenceId"));
        payload.put("queryId", optional(detail, "queryId"));
        payload.put("reuseDecision", optional(detail, "reuseDecision"));
        return new Result(List.of(table.put(evidence, QuoteTable.NOT_EXISTS)), payload);
    }

    /** Evidência tardia (§4 do desenho), gravada com o {@code CONSUMED} para não se repetir. */
    private Disposition recordLate(Waiter waiter, JsonNode detail, String eventId, String reason, Instant now) {
        String target = waiter.kind() == WorkRef.Kind.ENRICHMENT ? "ENRICHMENT" : waiter.insurerId();
        String queryId = optional(detail, "queryId");
        Map<String, AttributeValue> late = new HashMap<>();
        late.put(QuoteKeys.PK, s(QuoteKeys.quotePk(waiter.quoteId())));
        late.put(QuoteKeys.SK, s(QuoteKeys.lateSk(waiter.version(), target, queryId == null ? eventId : queryId)));
        late.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_LATE));
        late.put("reason", s(reason));
        late.put("eventId", s(eventId));
        late.put("receivedAt", s(now.toString()));
        putIfPresent(late, "status", optional(detail, waiter.kind() == WorkRef.Kind.ENRICHMENT ? "resultType" : "status"));
        putIfPresent(late, "queryRef", queryId);
        putIfPresent(late, "attemptRef", optional(detail, "attemptRef"));
        JsonNode offer = detail.get("offer");
        if (offer != null && offer.isObject()) {
            putIfPresent(late, "rawResponseRef", optional(offer, "rawResponseRef"));
            Map<String, Object> offerSummary = new LinkedHashMap<>();
            offerSummary.put("externalOfferId", optional(offer, "externalOfferId"));
            offerSummary.put("premiumTotal", optional(offer, "premiumTotal"));
            offerSummary.put("currency", optional(offer, "currency"));
            offerSummary.put("validUntil", optional(offer, "validUntil"));
            late.put("offerSummary", s(table.toJson(offerSummary)));
        }
        try {
            table.transact(List.of(table.put(late, null), table.put(consumed(eventId, now), QuoteTable.NOT_EXISTS)));
        } catch (TransactionCanceledException canceled) {
            if (!QuoteTable.isConditionFailure(canceled)) {
                throw canceled;
            }
            return Disposition.DISCARDED;
        }
        LOG.infof("%s result %s kept as evidence for workRef of quoteId=%s version=%d", reason, eventId,
                waiter.quoteId(), waiter.version());
        return "DUPLICATE".equals(reason) ? Disposition.DUPLICATE : Disposition.LATE;
    }

    private Map<String, AttributeValue> consumed(String eventId, Instant now) {
        return Map.of(QuoteKeys.PK, s(QuoteKeys.consumedPk(eventId)), QuoteKeys.SK, s(QuoteKeys.META),
                QuoteKeys.TYPE, s(QuoteKeys.TYPE_CONSUMED), "consumedAt", s(now.toString()));
    }

    private List<MissingField> missingFields(JsonNode detail) {
        List<MissingField> fields = new ArrayList<>();
        detail.path("missingFields").forEach(field -> fields.add(
                new MissingField(required(field, "path"), required(field, "reasonCode"))));
        return fields;
    }

    private static void putIfPresent(Map<String, AttributeValue> values, StringBuilder expression, String attribute,
            String value) {
        if (value != null) {
            values.put(":" + attribute, s(value));
            expression.append(", ").append(attribute).append(" = :").append(attribute);
        }
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String attribute, String value) {
        if (value != null) {
            item.put(attribute, s(value));
        }
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) {
            throw new IllegalArgumentException("event without " + field);
        }
        return value;
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String json(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "[]" : node.toString();
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Result(List<TransactWriteItem> writes, Map<String, Object> payload) {
    }

    /** Campos do waiter que a retomada usa. */
    private record Waiter(String quoteId, int version, WorkRef.Kind kind, String insurerId, String status,
            String resultEventId, Instant expiresAt) {

        static Waiter of(Map<String, AttributeValue> item) {
            String expiresAt = Attr.string(item, "expiresAt");
            return new Waiter(Attr.string(item, "quoteId"), (int) Attr.number(item, "version", 0),
                    WorkRef.Kind.valueOf(Attr.string(item, "kind")), Attr.string(item, "insurerId"),
                    Attr.string(item, "status"), Attr.string(item, "resultEventId"),
                    expiresAt == null ? null : Instant.parse(expiresAt));
        }
    }
}

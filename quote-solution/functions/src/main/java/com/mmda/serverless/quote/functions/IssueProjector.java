package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Operations;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Projeta os eventos operacionais da provider (§3, §4 e §7 do desenho, M5): ocorrências no {@code ISSUE#}, com o
 * {@code type} do evento em {@code issueType} e o {@code QUERYREF#} que autoriza o {@code RECONCILE}, e
 * reconciliações no {@code OPERATION#}. Idempotente pelo {@code CONSUMED#<eventId>} da mesma transação e pela
 * {@code revision}.
 */
@ApplicationScoped
public class IssueProjector {

    private static final Logger LOG = Logger.getLogger(IssueProjector.class);

    private final QuoteTable table;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    IssueProjector(QuoteTable table, ObjectMapper objectMapper, Clock clock) {
        this.table = table;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    enum Disposition {
        /** Gravado agora. */
        PROJECTED,
        /** Evento já consumido ou revisão anterior à gravada: nada muda. */
        DISCARDED,
        /** Resultado de reconciliação com outra organização que a da operação gravada: nada muda, e vai ao log. */
        FOREIGN_ORGANIZATION,
        /** Origem ou tipo fora do que a fila {@code issues} recebe: só registra no log. */
        IGNORED
    }

    /** Mensagem malformada lança {@link IllegalArgumentException}: volta à fila e, no limite, vai para a DLQ. */
    Disposition handle(String body) {
        JsonNode envelope = read(body);
        String source = envelope.path("source").asText(null);
        String detailType = envelope.path("detail-type").asText(null);
        if (!QuoteEvents.PROVIDER_SOURCE.equals(source)) {
            LOG.warnf("issue event from source %s (%s) ignored", source, detailType);
            return Disposition.IGNORED;
        }
        JsonNode detail = envelope.path("detail");
        Instant now = Instant.now(clock);
        if (QuoteEvents.QUERY_RECONCILED.equals(detailType)) {
            return projectOperation(detail, now);
        }
        if (QuoteEvents.OPERATION_ISSUE_RAISED.equals(detailType)
                || QuoteEvents.OPERATION_ISSUE_UPDATED.equals(detailType)) {
            return projectIssue(detail, now);
        }
        LOG.warnf("issue event %s ignored", detailType);
        return Disposition.IGNORED;
    }

    private Disposition projectIssue(JsonNode detail, Instant now) {
        String eventId = required(detail, "eventId");
        String issueId = required(detail, "issueId");
        String type = required(detail, "type");
        if (!Operations.IssueType.isProviderType(type)) {
            throw new IllegalArgumentException("unknown issue type " + type);
        }
        String status = QuoteIssues.Status.valueOf(required(detail, "status")).name();
        String firstSeenAt = instant(detail, "firstSeenAt");
        String lastSeenAt = instant(detail, "lastSeenAt");
        long revision = revision(detail);
        String organizationId = optional(detail, "organizationId");
        String queryId = optional(detail, "queryId");
        String quoteId = optional(detail, "quoteId");

        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":type", s(QuoteKeys.TYPE_ISSUE),
                ":issueId", s(issueId),
                ":issueType", s(type),
                ":status", s(status),
                ":source", s(Operations.PROVIDER_SOURCE),
                ":firstSeenAt", s(firstSeenAt),
                ":lastSeenAt", s(lastSeenAt),
                ":summary", s(required(detail, "safeSummary")),
                ":reconcilable", AttributeValue.fromBool(detail.path("reconcilable").asBoolean(false)),
                ":r", n(revision)));
        values.put(":now", s(now.toString()));
        StringBuilder expression = new StringBuilder("SET #type = :type, issueId = :issueId, issueType = :issueType, "
                + "#status = :status, #source = :source, firstSeenAt = :firstSeenAt, lastSeenAt = :lastSeenAt, "
                + "safeSummary = :summary, reconcilable = :reconcilable, revision = :r, projectedAt = :now");
        if (organizationId != null) {
            values.put(":org", s(organizationId));
            values.put(":gsi1pk", s(QuoteKeys.issuesIndexPk(organizationId)));
            values.put(":gsi1sk", s(lastSeenAt + "#" + issueId));
            expression.append(", organizationId = :org, ").append(QuoteKeys.GSI1_PK).append(" = :gsi1pk, ")
                    .append(QuoteKeys.GSI1_SK).append(" = :gsi1sk");
        }
        if (queryId != null) {
            values.put(":queryId", s(queryId));
            expression.append(", queryId = :queryId");
        }
        if (quoteId != null) {
            values.put(":quoteId", s(quoteId));
            expression.append(", quoteId = :quoteId");
        }
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(table.update(QuoteKeys.issuePk(issueId), QuoteKeys.META, expression.toString(),
                "attribute_not_exists(revision) OR revision < :r",
                Map.of("#type", QuoteKeys.TYPE, "#status", "status", "#source", "source"), values));
        writes.add(consumed(eventId, now));
        addQueryRef(writes, queryId, organizationId, now);
        return commit(writes, eventId, "issueId=" + issueId + " revision=" + revision);
    }

    private Disposition projectOperation(JsonNode detail, Instant now) {
        String eventId = required(detail, "eventId");
        String operationId = required(detail, "operationId");
        String organizationId = required(detail, "organizationId");
        String queryId = required(detail, "queryId");
        String status = Operations.OperationStatus.valueOf(required(detail, "status")).name();
        long revision = revision(detail);
        String occurredAt = detail.hasNonNull("occurredAt") ? instant(detail, "occurredAt") : now.toString();
        String owner = table.get(QuoteKeys.operationPk(operationId), QuoteKeys.META)
                .map(operation -> Attr.string(operation, "organizationId")).orElse(null);
        if (owner != null && !owner.equals(organizationId)) {
            LOG.warnf("event %s discarded: QueryReconciled of organization %s for operationId=%s of organization %s",
                    eventId, organizationId, operationId, owner);
            return Disposition.FOREIGN_ORGANIZATION;
        }

        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":type", s(QuoteKeys.TYPE_OPERATION),
                ":operationId", s(operationId),
                ":queryId", s(queryId),
                ":org", s(organizationId),
                ":status", s(status),
                ":summary", s(required(detail, "safeSummary")),
                ":r", n(revision),
                ":updatedAt", s(occurredAt)));
        List<TransactWriteItem> writes = new ArrayList<>();
        // INVARIANT: o OPERATION# gravado pela API guarda a organização do operador; um resultado de outra
        // organização não o toca
        writes.add(table.update(QuoteKeys.operationPk(operationId), QuoteKeys.META,
                "SET #type = :type, operationId = :operationId, queryId = if_not_exists(queryId, :queryId), "
                        + "organizationId = if_not_exists(organizationId, :org), #status = :status, "
                        + "safeSummary = :summary, revision = :r, updatedAt = :updatedAt",
                "(attribute_not_exists(revision) OR revision < :r) "
                        + "AND (attribute_not_exists(organizationId) OR organizationId = :org)",
                Map.of("#type", QuoteKeys.TYPE, "#status", "status"), values));
        writes.add(consumed(eventId, now));
        return commit(writes, eventId, "operationId=" + operationId + " revision=" + revision);
    }

    /**
     * A consulta passa a ser conhecida da organização, só pelo par de uma ocorrência. WHY: no
     * {@code QueryReconciled}, o par é o do pedido e não prova que a consulta é da organização (AsyncAPI da
     * provider); sem condição e com {@code if_not_exists}, a gravação nunca derruba a projeção e nunca troca a
     * organização de uma consulta já conhecida.
     */
    private void addQueryRef(List<TransactWriteItem> writes, String queryId, String organizationId, Instant now) {
        if (queryId == null || organizationId == null) {
            return;
        }
        writes.add(table.update(QuoteKeys.queryRefPk(queryId), QuoteKeys.META,
                "SET #type = :type, queryId = :queryId, organizationId = if_not_exists(organizationId, :org), "
                        + "firstSeenAt = if_not_exists(firstSeenAt, :now)",
                null, Map.of("#type", QuoteKeys.TYPE),
                Map.of(":type", s(QuoteKeys.TYPE_QUERYREF), ":queryId", s(queryId), ":org", s(organizationId),
                        ":now", s(now.toString()))));
    }

    private TransactWriteItem consumed(String eventId, Instant now) {
        return table.put(Map.of(
                QuoteKeys.PK, s(QuoteKeys.consumedPk(eventId)),
                QuoteKeys.SK, s(QuoteKeys.META),
                QuoteKeys.TYPE, s(QuoteKeys.TYPE_CONSUMED),
                "consumedAt", s(now.toString())), QuoteTable.NOT_EXISTS);
    }

    private Disposition commit(List<TransactWriteItem> writes, String eventId, String subject) {
        try {
            table.transact(writes);
            LOG.infof("projected event %s: %s", eventId, subject);
            return Disposition.PROJECTED;
        } catch (TransactionCanceledException canceled) {
            List<String> reasons = QuoteTable.reasons(canceled);
            if (!QuoteTable.isConditionFailure(canceled)) {
                throw canceled;
            }
            String why = reasons.size() > 1 && "ConditionalCheckFailed".equals(reasons.get(1))
                    ? "event already consumed" : "revision not newer than the projected one";
            LOG.infof("event %s discarded (%s): %s", eventId, why, subject);
            return Disposition.DISCARDED;
        }
    }

    private JsonNode read(String body) {
        try {
            JsonNode envelope = objectMapper.readTree(body == null ? "" : body);
            if (envelope == null || !envelope.isObject()) {
                throw new IllegalArgumentException("issue event is not a JSON object");
            }
            return envelope;
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("unreadable issue event", unreadable);
        }
    }

    private static String required(JsonNode detail, String field) {
        String value = optional(detail, field);
        if (value == null) {
            throw new IllegalArgumentException("issue event without " + field);
        }
        return value;
    }

    private static String optional(JsonNode detail, String field) {
        JsonNode value = detail.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }

    /** Instante normalizado para o formato do {@code Instant}, que ordena o {@code gsi1sk}. */
    private static String instant(JsonNode detail, String field) {
        try {
            return Instant.parse(required(detail, field)).toString();
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("issue event with malformed " + field, malformed);
        }
    }

    private static long revision(JsonNode detail) {
        JsonNode revision = detail.get("revision");
        if (revision == null || !revision.canConvertToLong() || revision.asLong() < 1) {
            throw new IllegalArgumentException("issue event without a valid revision");
        }
        return revision.asLong();
    }
}

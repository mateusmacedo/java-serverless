package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.GSI2;
import static com.mmda.serverless.provider.functions.Items.GSI2PK;
import static com.mmda.serverless.provider.functions.Items.GSI2SK;
import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.n;
import static com.mmda.serverless.provider.functions.Items.putIfPresent;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.IssueStatus;
import com.mmda.serverless.provider.domain.operations.IssueTransition;
import com.mmda.serverless.provider.domain.operations.IssueType;
import com.mmda.serverless.provider.domain.operations.OperationIssue;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Ocorrências operacionais da provider ({@code ISSUE#}, desenho, §5): cada mudança grava uma nova revisão, com
 * {@code firstSeenAt}, {@code lastSeenAt} e {@code revision}, e o {@code OperationIssueRaised} ou
 * {@code OperationIssueUpdated} no outbox, na mesma transação e condicionada à revisão lida. As ocorrências que
 * o reconciler resolve sozinho ficam no índice {@code ISSUE_OPEN} enquanto abertas.
 */
@Component
class OperationIssues {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationIssues.class);
    private static final int MAX_ROUNDS = 3;

    private final DynamoDbClient dynamoDb;
    private final Transactions transactions;
    private final OutboxStore outbox;
    private final ProviderProperties properties;
    private final Clock clock;

    OperationIssues(DynamoDbClient dynamoDb, Transactions transactions, OutboxStore outbox,
                    ProviderProperties properties, Clock clock) {
        this.dynamoDb = dynamoDb;
        this.transactions = transactions;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Problema observado. {@code target} identifica a ocorrência com o tipo; {@code organizationId},
     * {@code queryId} e {@code quoteId} são opcionais.
     */
    record Observation(IssueType type, String target, String organizationId, String queryId, String quoteId,
                       String safeSummary) {

        String issueId() {
            return Identities.issueId(type, target);
        }
    }

    /** Ocorrência aberta que o reconciler confere a cada ciclo. */
    record OpenIssue(String issueId, IssueType type, String target, String queryId) {
    }

    /**
     * Abre a ocorrência, ou reabre a resolvida. Uma ocorrência aberta só muda com {@code recurrence}, como outra
     * mensagem descartada do mesmo pedido. Devolve {@code true} quando gravou uma revisão.
     */
    boolean raise(Observation observation, boolean recurrence) {
        return change(observation, IssueStatus.OPEN, recurrence);
    }

    /** Resolve a ocorrência aberta; sem ocorrência, ou já resolvida, nada muda. */
    boolean resolve(IssueType type, String target, String safeSummary) {
        return change(new Observation(type, target, null, null, null, safeSummary), IssueStatus.RESOLVED, false);
    }

    /**
     * Leva a ocorrência que continua aberta para o fim do índice {@code ISSUE_OPEN}. WHY: o índice é lido em ordem e
     * com limite; sem isso, as que nunca se resolvem sozinhas ocupariam o lote e as outras não seriam revistas.
     */
    void deferReview(String issueId, Instant now) {
        try {
            dynamoDb.updateItem(builder -> builder
                    .tableName(properties.tableName())
                    .key(Items.key(Items.issuePk(issueId), META))
                    .updateExpression("SET " + GSI2SK + " = :now")
                    .conditionExpression(GSI2PK + " = :open")
                    .expressionAttributeValues(Map.of(":now", s(Timestamps.format(now)),
                            ":open", s(Items.ISSUE_OPEN))));
        } catch (ConditionalCheckFailedException resolved) {
            LOGGER.debug("Issue left the open index meanwhile: issueId={}", issueId);
        }
    }

    /** Ocorrências abertas dos tipos que se resolvem sozinhos, as revistas há mais tempo primeiro. */
    List<OpenIssue> openSelfResolving(int limit) {
        return dynamoDb.query(builder -> builder
                        .tableName(properties.tableName())
                        .indexName(GSI2)
                        .keyConditionExpression("gsi2pk = :open")
                        .expressionAttributeValues(Map.of(":open", s(Items.ISSUE_OPEN)))
                        .limit(limit))
                .items().stream()
                .map(item -> new OpenIssue(
                        Items.string(item, "issueId").orElseThrow(),
                        IssueType.valueOf(Items.string(item, "issueType").orElseThrow()),
                        Items.string(item, "target").orElseThrow(),
                        Items.string(item, "queryId").orElse(null)))
                .toList();
    }

    Optional<OperationIssue.State> load(String issueId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder
                        .tableName(properties.tableName())
                        .consistentRead(true)
                        .key(Items.key(Items.issuePk(issueId), META)))
                .item();
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OperationIssue.State(issueId,
                Items.string(item, "organizationId").orElse(null),
                IssueType.valueOf(Items.string(item, "issueType").orElseThrow()),
                IssueStatus.valueOf(Items.string(item, "status").orElseThrow()),
                Items.string(item, "queryId").orElse(null),
                Items.string(item, "quoteId").orElse(null),
                Items.string(item, "firstSeenAt").orElseThrow(),
                Items.string(item, "lastSeenAt").orElseThrow(),
                Items.string(item, "safeSummary").orElseThrow(),
                Items.number(item, "revision").orElseThrow()));
    }

    private boolean change(Observation observation, IssueStatus wanted, boolean recurrence) {
        String issueId = observation.issueId();
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            Optional<OperationIssue.State> current = load(issueId);
            IssueTransition transition = IssueTransition.of(current.map(OperationIssue.State::status).orElse(null),
                    wanted, recurrence);
            if (transition == IssueTransition.NONE) {
                return false;
            }
            Instant now = clock.instant();
            OperationIssue.State next = next(observation, current, wanted, now);
            OperationIssue event = next.event(Timestamps.format(now));
            Transactions.Result result = transactions.write(List.of(
                    putIssue(next, observation.target(), current.map(OperationIssue.State::revision).orElse(null), now),
                    outbox.put(new Settlement.OutboxEvent(event.eventId(), next.detailType(), event), now)));
            if (result.committed()) {
                LOGGER.warn("Operation issue {}: issueId={} type={} status={} revision={} queryId={}",
                        transition, issueId, observation.type(), wanted, next.revision(), next.queryId());
                return true;
            }
        }
        LOGGER.warn("Operation issue kept changing, left for the next cycle: issueId={}", issueId);
        return false;
    }

    private static OperationIssue.State next(Observation observation, Optional<OperationIssue.State> current,
                                             IssueStatus status, Instant now) {
        String seenAt = Timestamps.format(now);
        if (current.isEmpty()) {
            return new OperationIssue.State(observation.issueId(), observation.organizationId(), observation.type(),
                    status, observation.queryId(), observation.quoteId(), seenAt, seenAt, observation.safeSummary(), 1);
        }
        OperationIssue.State previous = current.get();
        return new OperationIssue.State(previous.issueId(),
                firstNonNull(observation.organizationId(), previous.organizationId()), previous.type(), status,
                firstNonNull(observation.queryId(), previous.queryId()),
                firstNonNull(observation.quoteId(), previous.quoteId()), previous.firstSeenAt(), seenAt,
                observation.safeSummary(), previous.revision() + 1);
    }

    private TransactWriteItem putIssue(OperationIssue.State state, String target, Long expectedRevision, Instant now) {
        Map<String, AttributeValue> item = Items.item(Items.issuePk(state.issueId()), META, Items.TYPE_ISSUE);
        item.put("issueId", s(state.issueId()));
        item.put("issueType", s(state.type().name()));
        item.put("status", s(state.status().name()));
        item.put("target", s(target));
        putIfPresent(item, "organizationId", state.organizationId());
        putIfPresent(item, "queryId", state.queryId());
        putIfPresent(item, "quoteId", state.quoteId());
        item.put("firstSeenAt", s(state.firstSeenAt()));
        item.put("lastSeenAt", s(state.lastSeenAt()));
        item.put("safeSummary", s(state.safeSummary()));
        item.put("reconcilable", AttributeValue.fromBool(state.event(state.lastSeenAt()).reconcilable()));
        item.put("revision", n(state.revision()));
        item.put("updatedAt", s(Timestamps.format(now)));
        if (state.status() == IssueStatus.OPEN && state.type().selfResolving()) {
            item.put(GSI2PK, s(Items.ISSUE_OPEN));
            item.put(GSI2SK, s(state.lastSeenAt()));
        }
        return TransactWriteItem.builder()
                .put(put -> {
                    put.tableName(properties.tableName()).item(item);
                    if (expectedRevision == null) {
                        put.conditionExpression("attribute_not_exists(pk)");
                    } else {
                        put.conditionExpression("revision = :revision")
                                .expressionAttributeValues(Map.of(":revision", n(expectedRevision)));
                    }
                })
                .build();
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }
}

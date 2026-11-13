package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.Items.META;
import static com.mmda.serverless.provider.functions.Items.n;
import static com.mmda.serverless.provider.functions.Items.s;

import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentRequest;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResultType;
import com.mmda.serverless.provider.domain.enrichment.Leadership;
import com.mmda.serverless.provider.domain.enrichment.ReuseIndexEntry;
import com.mmda.serverless.provider.domain.enrichment.ReusePlan;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Índice de reuso ({@code REUSE#}), liderança ({@code INFLIGHT#}) e seguidores
 * ({@code FOLLOWER#}) do
 * enriquecimento (desenho, §5 e §8). Líder e seguidores escrevem ou conferem o
 * mesmo {@code INFLIGHT#}, então
 * nenhum seguidor fica sem resposta (ADR 0015).
 */
@Component
class ReuseLedger {

    private static final Map<String, String> NAMES = Map.of("#state", "state", "#type", Items.TYPE, "#ttl", Items.TTL);
    private static final String IN_FLIGHT = Leadership.State.IN_FLIGHT.name();
    private static final String COMPLETED = Leadership.State.COMPLETED.name();

    private final DynamoDbClient dynamoDb;
    private final Transactions transactions;
    private final ProviderProperties properties;
    private final ReuseProperties reuse;

    ReuseLedger(DynamoDbClient dynamoDb, Transactions transactions, ProviderProperties properties,
            ReuseProperties reuse) {
        this.dynamoDb = dynamoDb;
        this.transactions = transactions;
        this.properties = properties;
        this.reuse = reuse;
    }

    enum Registration {
        REGISTERED,
        /** Outra entrega do mesmo pedido já se registrou nesta versão. */
        ALREADY_REGISTERED,
        /** A liderança mudou de versão ou concluiu antes do registro. */
        LEADERSHIP_CHANGED
    }

    /**
     * Seguidor registrado numa versão da liderança, com o necessário para receber o
     * fan-out.
     */
    record Follower(String workRef, String quoteId, int version, long fencingVersion) {
    }

    Optional<ReuseIndexEntry> reuseEntry(String queryKey) {
        Map<String, AttributeValue> item = get(Items.reusePk(queryKey), META);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReuseIndexEntry(
                Items.string(item, "queryId").orElseThrow(),
                Items.string(item, "evidenceId").orElseThrow(),
                EnrichmentResultType.valueOf(Items.string(item, "resultType").orElseThrow()),
                Timestamps.parse(Items.string(item, "reusableUntil").orElseThrow()),
                Items.number(item, "revision").orElseThrow(),
                Items.string(item, "policyVersion").orElse(null)));
    }

    Optional<Leadership> leadership(String queryKey) {
        Map<String, AttributeValue> item = get(Items.inflightPk(queryKey), META);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        long fencingVersion = Items.number(item, "fencingVersion").orElseThrow();
        return Optional.of(new Leadership(
                Leadership.State.valueOf(Items.string(item, "state").orElseThrow()),
                fencingVersion,
                Items.number(item, "firstFencingVersion").orElse(fencingVersion),
                Items.string(item, "leaderWorkRef").orElseThrow(),
                Items.string(item, "queryId").orElseThrow(),
                Timestamps.parse(Items.string(item, "leaseUntil").orElseThrow()),
                Items.string(item, "evidenceId").orElse(null),
                Items.string(item, "resultType").map(EnrichmentResultType::valueOf).orElse(null)));
    }

    /**
     * O pedido está registrado como seguidor numa das versões da cadeia corrente.
     */
    boolean isFollower(String queryKey, Leadership leadership, String workRef) {
        for (long version = leadership.fencingVersion(); version >= leadership.firstFencingVersion(); version--) {
            if (!get(Items.inflightPk(queryKey), Items.followerSk(version, workRef)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Seguidores das versões {@code from} a {@code to}, lidos com
     * {@code ConsistentRead}.
     */
    List<Follower> followers(String queryKey, long from, long to) {
        return dynamoDb.queryPaginator(builder -> builder
                .tableName(properties.tableName())
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND sk BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":pk", s(Items.inflightPk(queryKey)),
                        ":from", s(Items.followerPrefix(from)),
                        ":to", s(Items.followerPrefix(to) + "~"))))
                .items().stream()
                .map(item -> new Follower(
                        Items.string(item, "workRef").orElseThrow(),
                        Items.string(item, "quoteId").orElseThrow(),
                        Items.number(item, "version").orElseThrow().intValue(),
                        Items.number(item, "fencingVersion").orElseThrow()))
                .toList();
    }

    /**
     * Registro do seguidor (§8): {@code ConditionCheck} na liderança em voo da
     * versão lida e Put do
     * {@code FOLLOWER#<v>#<workRef>}, numa transação.
     */
    Registration register(String queryKey, long fencingVersion, EnrichmentRequest request, Instant now) {
        Map<String, AttributeValue> follower = Items.item(Items.inflightPk(queryKey),
                Items.followerSk(fencingVersion, request.workRef()), Items.TYPE_FOLLOWER);
        follower.put("workRef", s(request.workRef()));
        follower.put("quoteId", s(request.quoteId()));
        follower.put("version", n(request.version()));
        follower.put("fencingVersion", n(fencingVersion));
        follower.put("registeredAt", s(Timestamps.format(now)));
        follower.put(Items.TTL, n(tombstoneTtl(now)));
        Transactions.Result result = transactions.write(List.of(
                TransactWriteItem.builder()
                        .conditionCheck(check -> check
                                .tableName(properties.tableName())
                                .key(Items.key(Items.inflightPk(queryKey), META))
                                .conditionExpression("#state = :inFlight AND fencingVersion = :version")
                                .expressionAttributeNames(Map.of("#state", "state"))
                                .expressionAttributeValues(Map.of(
                                        ":inFlight", s(IN_FLIGHT),
                                        ":version", n(fencingVersion))))
                        .build(),
                TransactWriteItem.builder()
                        .put(put -> put
                                .tableName(properties.tableName())
                                .item(follower)
                                .conditionExpression("attribute_not_exists(pk)"))
                        .build()));
        if (result.committed()) {
            return Registration.REGISTERED;
        }
        return result.conditionFailedAt(0) ? Registration.LEADERSHIP_CHANGED : Registration.ALREADY_REGISTERED;
    }

    /**
     * Aquisição da liderança (§8), condicionada ao estado lido: item ausente, ou a
     * mesma versão com
     * {@code state = COMPLETED OR leaseUntil < :agora}. Reabrir uma lápide tira o
     * TTL dela.
     */
    TransactWriteItem lead(String queryKey, ReusePlan.Lead plan, String workRef, String queryId, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":inflight", s(Items.TYPE_INFLIGHT));
        values.put(":inFlight", s(IN_FLIGHT));
        values.put(":version", n(plan.fencingVersion()));
        values.put(":first", n(plan.firstFencingVersion()));
        values.put(":leader", s(workRef));
        values.put(":queryId", s(queryId));
        values.put(":leaseUntil", s(Timestamps.format(now.plus(reuse.lease()))));
        values.put(":now", s(Timestamps.format(now)));
        String condition;
        if (plan.expectedFencingVersion() == null) {
            condition = "attribute_not_exists(pk)";
        } else {
            condition = "fencingVersion = :expected AND (#state = :completed OR leaseUntil < :now)";
            values.put(":expected", n(plan.expectedFencingVersion()));
            values.put(":completed", s(COMPLETED));
        }
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.inflightPk(queryKey), META))
                        .updateExpression("SET #type = :inflight, #state = :inFlight, fencingVersion = :version, "
                                + "firstFencingVersion = :first, leaderWorkRef = :leader, queryId = :queryId, "
                                + "leaseUntil = :leaseUntil, acquiredAt = :now "
                                + "REMOVE evidenceId, resultType, completedAt, #ttl")
                        .conditionExpression(condition)
                        .expressionAttributeNames(NAMES)
                        .expressionAttributeValues(values))
                .build();
    }

    /**
     * WHY: um pedido que leu o índice antes da conclusão de outro líder e tenta a
     * liderança depois dela
     * repetiria a chamada; a revisão lida tem de continuar a mesma.
     */
    TransactWriteItem reuseUnchanged(String queryKey, Long expectedRevision) {
        return TransactWriteItem.builder()
                .conditionCheck(check -> {
                    check.tableName(properties.tableName()).key(Items.key(Items.reusePk(queryKey), META));
                    if (expectedRevision == null) {
                        check.conditionExpression("attribute_not_exists(pk)");
                    } else {
                        check.conditionExpression("revision = :revision")
                                .expressionAttributeValues(Map.of(":revision", n(expectedRevision)));
                    }
                })
                .build();
    }

    /**
     * Conclusão da liderança como lápide com TTL, só com a própria
     * {@code fencingVersion} (§8, T15). WHY: a
     * versão recomeça em 1 numa cadeia nova depois que o TTL apaga a lápide; o
     * {@code queryId} impede que a
     * consulta de uma cadeia antiga conclua a liderança atual.
     */
    TransactWriteItem complete(LeaderTerm term, String queryId, String evidenceId, String resultType, Instant now) {
        return TransactWriteItem.builder()
                .update(update -> update
                        .tableName(properties.tableName())
                        .key(Items.key(Items.inflightPk(term.queryKey()), META))
                        .updateExpression("SET #state = :completed, evidenceId = :evidenceId, "
                                + "resultType = :resultType, completedAt = :now, #ttl = :ttl")
                        .conditionExpression("fencingVersion = :version AND queryId = :queryId AND #state = :inFlight")
                        .expressionAttributeNames(Map.of("#state", "state", "#ttl", Items.TTL))
                        .expressionAttributeValues(Map.of(
                                ":completed", s(COMPLETED),
                                ":inFlight", s(IN_FLIGHT),
                                ":evidenceId", s(evidenceId),
                                ":resultType", s(resultType),
                                ":now", s(Timestamps.format(now)),
                                ":ttl", n(tombstoneTtl(now)),
                                ":version", n(term.fencingVersion()),
                                ":queryId", s(queryId))))
                .build();
    }

    /** Troca a evidência apontada pelo índice, condicionada à revisão lida (§8). */
    TransactWriteItem putReuse(String queryKey, ReuseIndexEntry entry, Long expectedRevision, Instant now) {
        Map<String, AttributeValue> item = Items.item(Items.reusePk(queryKey), META, Items.TYPE_REUSE);
        item.put("queryId", s(entry.queryId()));
        item.put("evidenceId", s(entry.evidenceId()));
        item.put("resultType", s(entry.resultType().name()));
        item.put("reusableUntil", s(Timestamps.format(entry.reusableUntil())));
        item.put("revision", n(entry.revision()));
        item.put("policyVersion", s(entry.policyVersion()));
        item.put("updatedAt", s(Timestamps.format(now)));
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

    private long tombstoneTtl(Instant now) {
        return now.getEpochSecond() + reuse.tombstoneSeconds();
    }

    private Map<String, AttributeValue> get(String pk, String sk) {
        return dynamoDb.getItem(builder -> builder
                .tableName(properties.tableName())
                .consistentRead(true)
                .key(Items.key(pk, sk)))
                .item();
    }
}

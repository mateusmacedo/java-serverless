package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Identities;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Chaves e atributos da tabela {@code provider-solution-<stage>-queries} (desenho, §5). Todo item tem
 * {@code type}; o filtro do stream depende dele (ADR 0015).
 */
final class Items {

    static final String PK = "pk";
    static final String SK = "sk";
    static final String TYPE = "type";
    static final String GSI2PK = "gsi2pk";
    static final String GSI2SK = "gsi2sk";
    static final String GSI2 = "gsi2";
    static final String TTL = "ttl";
    static final String META = "META";

    static final String TYPE_QUERY = "QUERY";
    static final String TYPE_ATTEMPT = "ATTEMPT";
    static final String TYPE_EVIDENCE = "EVIDENCE";
    static final String TYPE_OUTBOX = "OUTBOX";
    static final String TYPE_CONSUMED = "CONSUMED";
    static final String TYPE_QUOTA = "QUOTA";
    static final String TYPE_REUSE = "REUSE";
    static final String TYPE_INFLIGHT = "INFLIGHT";
    static final String TYPE_FOLLOWER = "FOLLOWER";
    static final String TYPE_INBOX = "INBOX";
    static final String TYPE_CORRELATION = "CORRELATION";

    static final String QUERY_IN_FLIGHT = "QUERY_IN_FLIGHT";
    static final String QUERY_UNKNOWN = "QUERY_UNKNOWN";
    static final String OUTBOX_PENDING = "OUTBOX_PENDING";
    static final String INBOX_RECEIVED = "INBOX_RECEIVED";

    private Items() {
    }

    static String queryPk(String queryId) {
        return "QUERY#" + queryId;
    }

    static String attemptSk(int sequence) {
        return "ATTEMPT#" + Identities.sequence(sequence);
    }

    static String evidenceSk(String evidenceId) {
        return "EVIDENCE#" + evidenceId;
    }

    static String reusePk(String queryKey) {
        return "REUSE#" + queryKey;
    }

    static String inflightPk(String queryKey) {
        return "INFLIGHT#" + queryKey;
    }

    /**
     * {@code FOLLOWER#<fencingVersion>#<workRef>}. WHY: a versão vai com zeros à esquerda, como a sequência do
     * {@code ATTEMPT#}, para o fan-out ler só a faixa de versões da cadeia por intervalo de {@code sk}.
     */
    static String followerSk(long fencingVersion, String workRef) {
        return followerPrefix(fencingVersion) + workRef;
    }

    static String followerPrefix(long fencingVersion) {
        return "FOLLOWER#%010d#".formatted(fencingVersion);
    }

    static String outboxPk(String eventId) {
        return "OUTBOX#" + eventId;
    }

    static String consumedPk(String eventId) {
        return "CONSUMED#" + eventId;
    }

    static String inboxPk(String provider, String externalEventId) {
        return "INBOX#" + provider + "#" + externalEventId;
    }

    static String correlationPk(String provider, String providerRequestId) {
        return "CORR#" + provider + "#" + providerRequestId;
    }

    static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(PK, s(pk), SK, s(sk));
    }

    static Map<String, AttributeValue> item(String pk, String sk, String type) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(pk));
        item.put(SK, s(sk));
        item.put(TYPE, s(type));
        return item;
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    static Optional<String> string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.s() == null ? Optional.empty() : Optional.of(value.s());
    }

    static Optional<Long> number(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? Optional.empty() : Optional.of(Long.parseLong(value.n()));
    }
}

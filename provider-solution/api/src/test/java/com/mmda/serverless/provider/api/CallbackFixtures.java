package com.mmda.serverless.provider.api;

import static com.mmda.serverless.provider.api.FlociIntegrationTest.DYNAMO_DB;
import static com.mmda.serverless.provider.api.FlociIntegrationTest.TABLE;

import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.CallbackSignature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Consultas da beta como o {@code insurerWorker} as grava e callbacks assinados como o {@code simCallbackSender}
 * os envia.
 */
final class CallbackFixtures {

    static final String OFFER = """
            {"outcome":"OFFER","offerNumber":"BETA-1A2B3C4D","insurerName":"Beta Seguros",\
            "premium":{"total":"2510.00","currency":"BRL"},\
            "paymentOptions":[{"installments":1,"installmentAmount":"2510.00"},\
            {"installments":12,"installmentAmount":"209.17"}],\
            "coverages":[{"code":"COLLISION","description":"Colis\\u00e3o, inc\\u00eandio e roubo","limit":"75000.00"}],\
            "deductibles":[{"coverage":"COLLISION","amount":"3000.00"}],\
            "expiresAt":"2026-12-01T00:00:00.000Z"}""";
    static final String DECLINED = "{\"outcome\":\"DECLINED\",\"declineCode\":\"RISK_NOT_ACCEPTED\"}";

    private CallbackFixtures() {
    }

    /** Consulta da beta em voo; {@code providerRequestId} nulo quando o {@code 202} ainda não foi gravado. */
    record Query(String workRef, String queryId, String requestEventId, String providerRequestId) {
    }

    static Query givenInFlightQuery(String providerRequestId) {
        return givenQuery("IN_FLIGHT", providerRequestId);
    }

    static Query givenQuery(String state, String providerRequestId) {
        String workRef = Identities.workRef("quote-" + UUID.randomUUID(), 1, Identities.INSURER, "beta");
        Query query = new Query(workRef, Identities.queryId(workRef), EventIds.request(workRef), providerRequestId);
        String startedAt = Timestamps.format(Instant.now().minusSeconds(5));
        Map<String, AttributeValue> meta = new HashMap<>(Map.of(
                "pk", s("QUERY#" + query.queryId()),
                "sk", s("META"),
                "type", s("QUERY"),
                "queryId", s(query.queryId()),
                "operation", s("QUOTE"),
                "provider", s("beta"),
                "insurerId", s("beta"),
                "state", s(state),
                "clientReference", s(query.queryId()),
                "workRef", s(workRef)));
        meta.put("quoteId", s(workRef.replace("wr_", "quote-")));
        meta.put("version", AttributeValue.fromN("1"));
        meta.put("organizationId", s("org-acme"));
        meta.put("requestEventId", s(query.requestEventId()));
        if ("IN_FLIGHT".equals(state)) {
            meta.put("gsi2pk", s("QUERY_IN_FLIGHT"));
            meta.put("gsi2sk", s(startedAt));
        }
        Map<String, AttributeValue> attempt = new HashMap<>(Map.of(
                "pk", s("QUERY#" + query.queryId()),
                "sk", s("ATTEMPT#0001"),
                "type", s("ATTEMPT"),
                "queryId", s(query.queryId()),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", s(startedAt)));
        if (providerRequestId != null) {
            meta.put("providerRequestId", s(providerRequestId));
            attempt.put("providerRequestId", s(providerRequestId));
            attempt.put("acceptedAt", s(startedAt));
        }
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(meta));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(attempt));
        return query;
    }

    /** Metade do worker no {@code CORR#}, como ele a grava depois do {@code 202}. */
    static void givenWorkerHalf(String providerRequestId, String queryId) {
        DYNAMO_DB.updateItem(builder -> builder
                .tableName(TABLE)
                .key(Map.of("pk", s("CORR#beta#" + providerRequestId), "sk", s("META")))
                .updateExpression("SET queryId = :queryId, #type = :type")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":queryId", s(queryId), ":type", s("CORRELATION"))));
    }

    static String callback(String externalEventId, String providerRequestId, String clientReference, String result) {
        StringBuilder body = new StringBuilder("{\"externalEventId\":\"").append(externalEventId).append('"');
        if (providerRequestId != null) {
            body.append(",\"providerRequestId\":\"").append(providerRequestId).append('"');
        }
        if (clientReference != null) {
            body.append(",\"clientReference\":\"").append(clientReference).append('"');
        }
        return body.append(",\"sentAt\":\"2026-11-11T20:00:00.000Z\",\"result\":").append(result).append('}')
                .toString();
    }

    static String sign(String body) {
        return CallbackSignature.sign(FlociIntegrationTest.CALLBACK_SECRET_VALUE.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    static String newEventId() {
        return "bev-" + UUID.randomUUID();
    }

    static String newProviderRequestId() {
        return "beta-" + UUID.randomUUID();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}

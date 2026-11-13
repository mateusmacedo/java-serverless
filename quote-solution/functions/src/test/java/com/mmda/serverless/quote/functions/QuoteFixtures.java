package com.mmda.serverless.quote.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import com.mmda.serverless.quote.domain.Usage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Cotações e eventos da provider nos formatos reais. A cotação é gravada com os mesmos itens da
 * transação de criação da API: {@code META}, {@code V#0001} e o outbox {@code StartQuoteRequested} e
 * {@code QuoteAccepted}.
 */
final class QuoteFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private QuoteFixtures() {
    }

    static QuoteSnapshot snapshot(Usage usage, int year, boolean salvage, String licenseDate) {
        return new QuoteSnapshot("AUTO", "customer-acme-ana", new QuoteSnapshot.Party("customer-acme-ana", "Ana Lima"),
                new QuoteSnapshot.Risk("vehicle-it", usage, "driver-it",
                        new QuoteSnapshot.Vehicle("vehicle-it", "Hatch 2021", "Fiat", "Argo", year, salvage),
                        new QuoteSnapshot.Driver("driver-it", "Ana Lima", licenseDate)),
                "COMPREHENSIVE",
                new QuoteSnapshot.CoverageProfile("COMPREHENSIVE", "Completa", "Colisão e terceiros",
                        List.of(new QuoteSnapshot.Coverage("COLLISION", "Colisão", "90000.00"))),
                "2026-12-01");
    }

    /** Grava a cotação como a API; devolve os {@code eventId} do outbox (início e aceitação). */
    static Created createQuote(DynamoDbClient dynamoDb, String quoteId, QuoteSnapshot snapshot, Instant acceptedAt)
            throws Exception {
        String now = acceptedAt.toString();
        String deadline = acceptedAt.plusSeconds(300).toString();
        String pk = QuoteKeys.quotePk(quoteId);
        put(dynamoDb, Map.ofEntries(
                Map.entry("pk", s(pk)), Map.entry("sk", s("META")), Map.entry("type", s("QUOTE")),
                Map.entry("quoteId", s(quoteId)), Map.entry("organizationId", s("org-acme")),
                Map.entry("createdBy", s("user-1")), Map.entry("currentVersion", n(1)),
                Map.entry("createdAt", s(now)), Map.entry("updatedAt", s(now)), Map.entry("phase", s("RECEIVED")),
                Map.entry("offerCount", n(0)), Map.entry("gsi1pk", s("ORG#org-acme")),
                Map.entry("gsi1sk", s(now + "#" + quoteId))));
        put(dynamoDb, Map.ofEntries(
                Map.entry("pk", s(pk)), Map.entry("sk", s("V#0001")), Map.entry("type", s("VERSION")),
                Map.entry("quoteId", s(quoteId)), Map.entry("version", n(1)),
                Map.entry("snapshot", s(MAPPER.writeValueAsString(snapshot))), Map.entry("inputHash", s("hash")),
                Map.entry("phase", s("RECEIVED")), Map.entry("revision", n(1)),
                Map.entry("workflowVersion", s("it")), Map.entry("executionName", s(QuoteKeys.executionName(quoteId, 1))),
                Map.entry("deadlineAt", s(deadline)), Map.entry("createdAt", s(now)), Map.entry("updatedAt", s(now)),
                Map.entry("gsi2pk", s("ACTIVE_VERSION")), Map.entry("gsi2sk", s(deadline))));
        String startEventId = UUID.randomUUID().toString();
        String acceptedEventId = UUID.randomUUID().toString();
        put(dynamoDb, outbox(startEventId, QuoteEvents.START_QUOTE_REQUESTED,
                QuoteEvents.startQuoteRequested(startEventId, acceptedAt, quoteId, 1, "org-acme"), quoteId, acceptedAt));
        put(dynamoDb, outbox(acceptedEventId, QuoteEvents.QUOTE_ACCEPTED,
                QuoteEvents.quoteAccepted(acceptedEventId, acceptedAt, quoteId, 1, "org-acme"), quoteId, acceptedAt));
        return new Created(startEventId, acceptedEventId);
    }

    record Created(String startEventId, String acceptedEventId) {
    }

    static Map<String, AttributeValue> outbox(String eventId, String detailType, Map<String, Object> detail,
            String quoteId, Instant createdAt) throws Exception {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(QuoteKeys.outboxPk(eventId)));
        item.put("sk", s("META"));
        item.put("type", s("OUTBOX"));
        item.put("eventId", s(eventId));
        item.put("detailType", s(detailType));
        item.put("detail", s(MAPPER.writeValueAsString(detail)));
        item.put("quoteId", s(quoteId));
        item.put("version", n(1));
        item.put("createdAt", s(createdAt.toString()));
        item.put("attempts", n(0));
        item.put("gsi2pk", s("OUTBOX_PENDING"));
        item.put("gsi2sk", s(createdAt.toString()));
        return item;
    }

    /** {@code InsurerResultRecorded} como a provider publica, dentro do envelope do EventBridge. */
    static String insurerResult(String workRef, String quoteId, String status, String eventId) {
        ObjectNode detail = MAPPER.createObjectNode()
                .put("eventId", eventId)
                .put("occurredAt", Instant.now().toString())
                .put("schemaVersion", "1")
                .put("workRef", workRef)
                .put("quoteId", quoteId)
                .put("version", 1)
                .put("insurerId", "alpha")
                .put("queryId", "q_" + workRef.substring(3))
                .put("attemptRef", "ATTEMPT#0001")
                .put("status", status);
        detail.putArray("missingFields");
        if ("QUOTED".equals(status)) {
            ObjectNode offer = detail.putObject("offer")
                    .put("externalOfferId", "ALPHA-123")
                    .put("insurerName", "Alpha Seguros")
                    .put("premiumTotal", "2450.90")
                    .put("currency", "BRL")
                    .put("validUntil", Instant.now().plusSeconds(1800).toString())
                    .put("rawResponseRef", "s3://provider-solution-local-payloads/raw/q_1.json");
            offer.putArray("installments").addObject().put("count", 1).put("amount", "2450.90");
            offer.putArray("coverages").addObject().put("code", "COLLISION").put("label", "Colisão")
                    .put("insuredAmount", "90000.00");
            offer.putArray("deductibles").addObject().put("coverageCode", "COLLISION").put("amount", "3500.00");
        } else {
            detail.put("reasonCode", "RISK_NOT_ACCEPTED");
        }
        return envelope("InsurerResultRecorded", detail);
    }

    /** {@code EnrichmentResolved} com o perfil normalizado do veículo e do condutor. */
    static String enrichmentResult(String workRef, String quoteId, String resultType, int vehicleYear, String eventId) {
        ObjectNode detail = MAPPER.createObjectNode()
                .put("eventId", eventId)
                .put("occurredAt", Instant.now().toString())
                .put("schemaVersion", "1")
                .put("workRef", workRef)
                .put("quoteId", quoteId)
                .put("version", 1)
                .put("queryId", "q_enrichment")
                .put("evidenceId", "ev_1")
                .put("reuseDecision", "MISS")
                .put("resultType", resultType)
                .put("reusableUntil", Instant.now().plusSeconds(900).toString());
        if ("FOUND".equals(resultType)) {
            ObjectNode result = detail.putObject("result");
            result.putObject("vehicle").put("year", vehicleYear).put("fipeValue", "95000.00");
            result.putObject("principalDriver").put("claimsLast5Years", 0);
        } else if ("NOT_FOUND".equals(resultType)) {
            detail.putArray("notFoundRefs").add("vehicleRef");
        } else {
            detail.put("errorClass", "PROVIDER_UNAVAILABLE");
        }
        return envelope("EnrichmentResolved", detail);
    }

    private static String envelope(String detailType, ObjectNode detail) {
        ObjectNode envelope = MAPPER.createObjectNode()
                .put("version", "0")
                .put("id", UUID.randomUUID().toString())
                .put("source", "mmda.provider")
                .put("detail-type", detailType)
                .put("account", "000000000000")
                .put("time", Instant.now().toString())
                .put("region", "us-east-1");
        envelope.putArray("resources");
        envelope.set("detail", detail);
        return envelope.toString();
    }

    /** Evento de SQS com uma mensagem, no formato que a Lambda recebe. */
    static LambdaEvents.SqsEvent sqs(String body) {
        return new LambdaEvents.SqsEvent(List.of(new LambdaEvents.SqsMessage(UUID.randomUUID().toString(), body)));
    }

    private static void put(DynamoDbClient dynamoDb, Map<String, AttributeValue> item) {
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(item));
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}

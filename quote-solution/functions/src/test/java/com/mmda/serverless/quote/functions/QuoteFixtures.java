package com.mmda.serverless.quote.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.Insurers;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import com.mmda.serverless.quote.domain.Usage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /** {@code InsurerResultRecorded} da alpha como a provider publica, dentro do envelope do EventBridge. */
    static String insurerResult(String workRef, String quoteId, String status, String eventId) {
        return insurerResult(workRef, quoteId, Insurers.ALPHA, status, eventId, "q_" + workRef.substring(3),
                "ATTEMPT#0001");
    }

    /**
     * {@code InsurerResultRecorded} da seguradora informada, com o {@code queryId} e o {@code attemptRef} que a
     * provider deriva do {@code workRef} (§5 do desenho). A beta, que responde por callback, publica o mesmo
     * contrato da alpha.
     */
    static String insurerResult(String workRef, String quoteId, String insurerId, String status, String eventId) {
        String queryId = providerQueryId(workRef);
        return insurerResult(workRef, quoteId, insurerId, status, eventId, queryId, queryId + "#0001");
    }

    /** {@code queryId} que a provider grava para o pedido: {@code q_} + 32 hexadecimais do SHA-256 do {@code workRef}. */
    static String providerQueryId(String workRef) {
        return "q_" + Hashes.sha256Hex(workRef).substring(0, 32);
    }

    /** {@code eventId} do {@code InsurerResultRecorded}: UUIDv5 do {@code queryId}, igual em toda republicação. */
    static String insurerResultEventId(String workRef) {
        return Hashes.uuidV5(providerQueryId(workRef)).toString();
    }

    private static String insurerResult(String workRef, String quoteId, String insurerId, String status, String eventId,
            String queryId, String attemptRef) {
        ObjectNode detail = MAPPER.createObjectNode()
                .put("eventId", eventId)
                .put("occurredAt", Instant.now().toString())
                .put("schemaVersion", "1")
                .put("workRef", workRef)
                .put("quoteId", quoteId)
                .put("version", 1)
                .put("insurerId", insurerId)
                .put("queryId", queryId)
                .put("attemptRef", attemptRef)
                .put("status", status);
        ArrayNode missingFields = detail.putArray("missingFields");
        switch (status) {
            case "QUOTED" -> {
                String premium = Insurers.ALPHA.equals(insurerId) ? "2450.90" : "2299.00";
                ObjectNode offer = detail.putObject("offer")
                        .put("externalOfferId", insurerId.toUpperCase(Locale.ROOT) + "-123")
                        .put("insurerName", Insurers.nameOf(insurerId).orElseThrow())
                        .put("premiumTotal", premium)
                        .put("currency", "BRL")
                        .put("validUntil", Instant.now().plusSeconds(1800).toString())
                        .put("rawResponseRef", "raw/" + queryId + "/0001.json");
                offer.putArray("installments").addObject().put("count", 1).put("amount", premium);
                offer.putArray("coverages").addObject().put("code", "COLLISION").put("label", "Colisão")
                        .put("insuredAmount", "90000.00");
                offer.putArray("deductibles").addObject().put("coverageCode", "COLLISION").put("amount", "3500.00");
            }
            case "NEEDS_INFORMATION" -> {
                detail.put("reasonCode", "INSURER_NEEDS_INFORMATION");
                missingFields.addObject().put("path", "risk.principalDriver.licenseDate")
                        .put("reasonCode", "MISSING_LICENSE_DATE");
            }
            default -> detail.put("reasonCode", "RISK_NOT_ACCEPTED");
        }
        return envelope("InsurerResultRecorded", detail);
    }

    /** {@code EnrichmentResolved} de um {@code MISS}, com o perfil normalizado do veículo e do condutor. */
    static String enrichmentResult(String workRef, String quoteId, String resultType, int vehicleYear, String eventId) {
        return enrichmentResult(workRef, quoteId, eventId, EvidenceRef.miss(workRef), resultType, vehicleYear,
                List.of("vehicleRef"));
    }

    /**
     * {@code EnrichmentResolved} com a referência da evidência informada; um campo nulo de {@code evidence} fica
     * fora do evento. {@code notFoundRefs} só entra em {@code NOT_FOUND}.
     */
    static String enrichmentResult(String workRef, String quoteId, String eventId, EvidenceRef evidence,
            String resultType, int vehicleYear, List<String> notFoundRefs) {
        ObjectNode detail = MAPPER.createObjectNode()
                .put("eventId", eventId)
                .put("occurredAt", Instant.now().toString())
                .put("schemaVersion", "1")
                .put("workRef", workRef)
                .put("quoteId", quoteId)
                .put("version", 1);
        putIfPresent(detail, "queryId", evidence.queryId());
        putIfPresent(detail, "evidenceId", evidence.evidenceId());
        putIfPresent(detail, "reuseDecision", evidence.reuseDecision());
        putIfPresent(detail, "policyVersion", evidence.policyVersion());
        detail.put("resultType", resultType);
        if ("FOUND".equals(resultType)) {
            ObjectNode result = detail.putObject("result");
            result.putObject("vehicle").put("ref", "vehicle-hatch-2021").put("make", "Volkswagen").put("model", "Polo")
                    .put("year", vehicleYear).put("marketValue", "75000.00");
            result.putObject("principalDriver").put("ref", "driver-acme-ana").put("licenseYears", 14)
                    .put("claimsLast5Years", 1);
            detail.put("reusableUntil", Instant.now().plusSeconds(900).toString());
        } else if ("NOT_FOUND".equals(resultType)) {
            notFoundRefs.forEach(detail.putArray("notFoundRefs")::add);
            detail.put("reusableUntil", Instant.now().plusSeconds(900).toString());
        } else {
            detail.put("errorClass", "PROVIDER_UNAVAILABLE");
        }
        return envelope("EnrichmentResolved", detail);
    }

    /**
     * Referência da evidência que a provider publica no {@code EnrichmentResolved}, com os identificadores
     * derivados pelas regras do AsyncAPI dela.
     */
    record EvidenceRef(String queryId, String evidenceId, String reuseDecision, String policyVersion) {

        static final String POLICY_VERSION = "reuse-v1";

        /** Evidência da consulta aberta pelo próprio pedido ({@code MISS}). */
        static EvidenceRef miss(String workRef) {
            return shared(workRef, "MISS");
        }

        /** Evidência da consulta de um líder, reusada ({@code HIT}) ou aguardada ({@code JOIN}) por outro pedido. */
        static EvidenceRef shared(String leaderWorkRef, String reuseDecision) {
            String queryId = "q_" + Hashes.sha256Hex(leaderWorkRef).substring(0, 32);
            return new EvidenceRef(queryId, "ev_" + Hashes.sha256Hex(queryId + "|1").substring(0, 32), reuseDecision,
                    POLICY_VERSION);
        }

        EvidenceRef withReuseDecision(String decision) {
            return new EvidenceRef(queryId, evidenceId, decision, policyVersion);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
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

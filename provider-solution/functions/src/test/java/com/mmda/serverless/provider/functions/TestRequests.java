package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pedidos da quote como chegam às filas da provider: o envelope do EventBridge
 * com o {@code detail} do
 * AsyncAPI da quote-solution. Cada pedido usa uma cotação nova, para os testes
 * não se cruzarem.
 */
final class TestRequests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestRequests() {
    }

    record Request(String body, String workRef, String queryId, String eventId, String quoteId) {
    }

    static Request enrichment(String vehicleRef, Instant deadlineAt) {
        return enrichment(vehicleRef, deadlineAt, FlociIntegrationTest.ENRICHMENT_PROVIDER);
    }

    static Request enrichment(String vehicleRef, Instant deadlineAt, String provider) {
        return enrichment(vehicleRef, "driver-ana", deadlineAt, provider);
    }

    /**
     * Pedido com outro condutor: a {@code queryKey} muda, então não reutiliza
     * consultas de outros testes.
     */
    static Request enrichment(String vehicleRef, String principalDriverRef, Instant deadlineAt, String provider) {
        String quoteId = "quote-" + UUID.randomUUID();
        String workRef = Identities.workRef(quoteId, 1, Identities.ENRICHMENT, null);
        Map<String, Object> detail = envelopeFields(workRef, quoteId, deadlineAt);
        detail.put("provider", provider);
        detail.put("operation", "VEHICLE_DRIVER_PROFILE");
        detail.put("contractVersion", "1");
        detail.put("params", Map.of("vehicleRef", vehicleRef, "principalDriverRef", principalDriverRef));
        return request("EnrichmentRequested", detail, workRef, quoteId);
    }

    static Request insurer(String vehicleRef, Instant deadlineAt) {
        return insurer(vehicleRef, deadlineAt, "alpha");
    }

    static Request insurer(String vehicleRef, Instant deadlineAt, String insurerId) {
        String quoteId = "quote-" + UUID.randomUUID();
        String workRef = Identities.workRef(quoteId, 1, Identities.INSURER, insurerId);
        Map<String, Object> detail = envelopeFields(workRef, quoteId, deadlineAt);
        detail.put("insurerId", insurerId);
        detail.put("risk", Map.of(
                "product", "AUTO",
                "usage", "PERSONAL",
                "vehicle", Map.of("ref", vehicleRef, "year", 2019),
                "principalDriver", Map.of("ref", "driver-ana", "licenseDate", "2015-03-10"),
                "coverageProfile", "COMPREHENSIVE",
                "desiredStartDate", "2026-12-01"));
        detail.put("enrichment", Map.of("resultType", "FOUND", "vehicleYear", 2019));
        return request("InsurerQuoteRequested", detail, workRef, quoteId);
    }

    private static Map<String, Object> envelopeFields(String workRef, String quoteId, Instant deadlineAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", EventIds.request(workRef));
        detail.put("occurredAt", Timestamps.format(Instant.now()));
        detail.put("schemaVersion", "1");
        detail.put("workRef", workRef);
        detail.put("organizationId", "org-acme");
        detail.put("quoteId", quoteId);
        detail.put("version", 1);
        detail.put("deadlineAt", Timestamps.format(deadlineAt));
        return detail;
    }

    private static Request request(String detailType, Map<String, Object> detail, String workRef, String quoteId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", "0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("detail-type", detailType);
        envelope.put("source", "mmda.quote");
        envelope.put("account", "000000000000");
        envelope.put("time", Timestamps.format(Instant.now()));
        envelope.put("region", "us-east-1");
        envelope.put("resources", java.util.List.of());
        envelope.put("detail", detail);
        return new Request(JSON.writeValueAsString(envelope), workRef, Identities.queryId(workRef),
                (String) detail.get("eventId"), quoteId);
    }
}

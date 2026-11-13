package com.mmda.serverless.quote.functions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Métricas do {@code reconciler} em CloudWatch Embedded Metric Format (§9 do desenho, "Alarmes (M4)"). Os
 * alarmes do {@code serverless.yml} usam o mesmo namespace, os mesmos nomes e a dimensão {@code Stage}.
 */
@ApplicationScoped
public class ReconcilerMetrics {

    static final String EXPIRED_TASKS = "ExpiredTasks";
    static final String OUTBOX_PENDING_AGE_SECONDS = "OutboxPendingAgeSeconds";
    static final String STAGE_DIMENSION = "Stage";

    private final ObjectMapper objectMapper;
    private final ReconcilerConfig config;

    ReconcilerMetrics(ObjectMapper objectMapper, ReconcilerConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * Uma linha EMF por execução, com os dois valores sempre presentes. WHY: vai direto ao {@code stdout}, sem o
     * prefixo do log do Quarkus, porque o CloudWatch só extrai a métrica de uma linha que é um objeto JSON.
     */
    void emit(Instant now, long expiredTasks, long outboxPendingAgeSeconds) {
        System.out.println(document(now, expiredTasks, outboxPendingAgeSeconds));
    }

    String document(Instant now, long expiredTasks, long outboxPendingAgeSeconds) {
        Map<String, Object> directive = new LinkedHashMap<>();
        directive.put("Namespace", config.metricsNamespace());
        directive.put("Dimensions", List.of(List.of(STAGE_DIMENSION)));
        directive.put("Metrics", List.of(
                Map.of("Name", EXPIRED_TASKS, "Unit", "Count"),
                Map.of("Name", OUTBOX_PENDING_AGE_SECONDS, "Unit", "Seconds")));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Timestamp", now.toEpochMilli());
        metadata.put("CloudWatchMetrics", List.of(directive));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("_aws", metadata);
        document.put(STAGE_DIMENSION, config.stage());
        document.put(EXPIRED_TASKS, expiredTasks);
        document.put(OUTBOX_PENDING_AGE_SECONDS, outboxPendingAgeSeconds);
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

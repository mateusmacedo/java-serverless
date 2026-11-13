package com.mmda.serverless.provider.functions;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Métricas próprias em EMF (CloudWatch Embedded Metric Format), uma linha JSON
 * por registro na saída da Lambda
 * (desenho, §9). Todas levam a dimensão {@code Stage}; as de provedor, também
 * {@code Provider}. Os alarmes do
 * serverless.yml leem exatamente esses nomes.
 */
@Component
class Metrics {

    static final String UNMATCHED_CALLBACKS = "UnmatchedCallbacks";
    static final String UNKNOWN_QUERIES = "UnknownQueries";
    static final String OUTBOX_PENDING_AGE_SECONDS = "OutboxPendingAgeSeconds";
    static final String PROVIDER_CALLS = "ProviderCalls";
    static final String PROVIDER_FAILURES = "ProviderFailures";

    private final OperationsProperties properties;
    private final MetricsSink sink;
    private final EventJson json;
    private final Clock clock;

    Metrics(OperationsProperties properties, MetricsSink sink, EventJson json, Clock clock) {
        this.properties = properties;
        this.sink = sink;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Leituras do reconciler a cada ciclo: callbacks {@code UNMATCHED}, consultas
     * {@code UNKNOWN} e outbox.
     */
    void reconcilerGauges(long unmatchedCallbacks, long unknownQueries, long outboxPendingAgeSeconds) {
        Map<String, Number> values = new LinkedHashMap<>();
        values.put(UNMATCHED_CALLBACKS, unmatchedCallbacks);
        values.put(UNKNOWN_QUERIES, unknownQueries);
        values.put(OUTBOX_PENDING_AGE_SECONDS, outboxPendingAgeSeconds);
        emit(Map.of(), values, Map.of(OUTBOX_PENDING_AGE_SECONDS, "Seconds"));
    }

    /**
     * Uma chamada ao fornecedor; {@code failed} quando ela não trouxe resposta de
     * negócio, como falha de rede, 5xx,
     * 4xx ou resposta ilegível. O alarme compara as duas somas por provedor.
     */
    void providerCall(String provider, boolean failed) {
        Map<String, Number> values = new LinkedHashMap<>();
        values.put(PROVIDER_CALLS, 1);
        values.put(PROVIDER_FAILURES, failed ? 1 : 0);
        emit(Map.of("Provider", provider), values, Map.of());
    }

    private void emit(Map<String, String> dimensions, Map<String, Number> values, Map<String, String> units) {
        Map<String, Object> record = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        names.add("Stage");
        names.addAll(dimensions.keySet());
        List<Map<String, String>> metrics = values.keySet().stream()
                .map(name -> Map.of("Name", name, "Unit", units.getOrDefault(name, "Count")))
                .toList();
        record.put("_aws", Map.of(
                "Timestamp", clock.millis(),
                "CloudWatchMetrics", List.of(Map.of(
                        "Namespace", properties.metricsNamespace(),
                        "Dimensions", List.of(names),
                        "Metrics", metrics))));
        record.put("Stage", properties.stage());
        record.putAll(dimensions);
        record.putAll(values);
        sink.emit(json.write(record));
    }

    /**
     * Destino das linhas EMF; na Lambda, a saída padrão, que vai para o CloudWatch
     * Logs.
     */
    @FunctionalInterface
    interface MetricsSink {

        void emit(String line);
    }
}

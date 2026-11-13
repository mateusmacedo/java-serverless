package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReconcilerMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emfLineDeclaresBothMetricsUnderTheStageDimension() throws Exception {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        ReconcilerMetrics metrics = new ReconcilerMetrics(MAPPER, new TestConfig());

        JsonNode document = MAPPER.readTree(metrics.document(now, 2, 45));

        JsonNode directive = document.at("/_aws/CloudWatchMetrics/0");
        assertThat(document.at("/_aws/Timestamp").asLong()).isEqualTo(now.toEpochMilli());
        assertThat(directive.get("Namespace").asText()).isEqualTo("mmda/quote-solution");
        assertThat(directive.get("Dimensions").toString()).isEqualTo("[[\"Stage\"]]");
        assertThat(directive.get("Metrics").findValuesAsText("Name"))
                .containsExactly(ReconcilerMetrics.EXPIRED_TASKS, ReconcilerMetrics.OUTBOX_PENDING_AGE_SECONDS);
        assertThat(directive.get("Metrics").findValuesAsText("Unit")).containsExactly("Count", "Seconds");
        assertThat(document.get("Stage").asText()).isEqualTo("local");
        assertThat(document.get(ReconcilerMetrics.EXPIRED_TASKS).asLong()).isEqualTo(2);
        assertThat(document.get(ReconcilerMetrics.OUTBOX_PENDING_AGE_SECONDS).asLong()).isEqualTo(45);
    }

    private static final class TestConfig implements ReconcilerConfig {

        @Override
        public String resultsQueueUrl() {
            return "results";
        }

        @Override
        public String resultsDeadLetterQueueUrl() {
            return "results-dlq";
        }

        @Override
        public int dlqMaxAttempts() {
            return 3;
        }

        @Override
        public int stuckVersionMarginSeconds() {
            return 120;
        }

        @Override
        public int resumingRetryAfterSeconds() {
            return 30;
        }

        @Override
        public String metricsNamespace() {
            return "mmda/quote-solution";
        }

        @Override
        public String stage() {
            return "local";
        }
    }
}

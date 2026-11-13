package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.sqsEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.QuotaWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T21: pedidos de vários workflows ao mesmo provedor, ao mesmo tempo, dividem a quota da janela (§5
 * {@code QUOTA#}, §9). O limite vale para todos, não só para a concorrência do Map de uma cotação. Cada pedido
 * é de uma cotação nova, e os provedores do teste têm quota de {@link #T21_QUOTA_PER_MINUTE} por minuto.
 */
class QuotaAcrossWorkflowsIT extends FlociIntegrationTest {

    private static final int WORKFLOWS = 8;
    private static final Duration DEADLINE = Duration.ofMinutes(4);
    /** Folga para todos os pedidos caberem na mesma janela da quota. */
    private static final Duration WINDOW_MARGIN = Duration.ofSeconds(20);

    @Autowired
    private InsurerWorkerFunction insurerWorker;

    @Autowired
    private EnrichmentWorkerFunction enrichmentWorker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void t21_insurerQuotaIsSharedByEveryWorkflowAndTheRestIsDelayedToTheNextWindow() throws Exception {
        QuotaWindow window = windowWithMargin();
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));
        List<TestRequests.Request> requests = IntStream.range(0, WORKFLOWS)
                .mapToObj(index -> TestRequests.insurer("vehicle-hatch-2019", Instant.now().plus(DEADLINE), T21_INSURER))
                .toList();

        runTogether(requests, request -> insurerWorker.apply(sqsEvent(request.body())));

        assertThat(totalCalls(requests)).isEqualTo(T21_QUOTA_PER_MINUTE);
        assertThat(used(T21_INSURER, window)).isEqualTo(T21_QUOTA_PER_MINUTE);
        List<TestRequests.Request> quoted = requests.stream()
                .filter(request -> !item("QUERY#" + request.queryId(), "META").isEmpty())
                .toList();
        assertThat(quoted).hasSize(T21_QUOTA_PER_MINUTE).allSatisfy(request ->
                assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED"));
        assertThat(EnrichmentWorkerIT.queuedMessages(INSURER_QUEUE_URL))
                .as("the requests over the quota go back to the queue, delayed to the next window")
                .isEqualTo(WORKFLOWS - T21_QUOTA_PER_MINUTE);
        SQS.purgeQueue(builder -> builder.queueUrl(INSURER_QUEUE_URL));
    }

    @Test
    void t21_enrichmentQuotaIsSharedByEveryWorkflowAndTheRestFailsWhenTheWindowDoesNotFit() throws Exception {
        QuotaWindow window = windowWithMargin();
        List<TestRequests.Request> requests = IntStream.range(0, WORKFLOWS)
                .mapToObj(index -> TestRequests.enrichment("vehicle-hatch-2019", "driver-t21-" + index + "-"
                        + System.nanoTime(), window.end(), T21_ENRICHMENT))
                .toList();

        runTogether(requests, request -> enrichmentWorker.apply(sqsEvent(request.body())));

        assertThat(totalCalls(requests)).isEqualTo(T21_QUOTA_PER_MINUTE);
        assertThat(used(T21_ENRICHMENT, window)).isEqualTo(T21_QUOTA_PER_MINUTE);
        List<JsonNode> resolved = requests.stream()
                .map(request -> outboxDetail(EventIds.enrichmentResolved(request.queryId(), request.workRef())))
                .toList();
        assertThat(resolved).filteredOn(detail -> "FOUND".equals(detail.path("resultType").asString()))
                .hasSize(T21_QUOTA_PER_MINUTE);
        assertThat(resolved).filteredOn(detail -> "FAILED".equals(detail.path("resultType").asString()))
                .hasSize(WORKFLOWS - T21_QUOTA_PER_MINUTE)
                .allSatisfy(detail -> {
                    assertThat(detail.path("errorClass").asString()).isEqualTo("QUOTA_EXHAUSTED");
                    assertThat(detail.path("reuseDecision").asString()).isEqualTo("MISS");
                });
    }

    /** Janela corrente com pelo menos {@link #WINDOW_MARGIN} pela frente; senão, espera a próxima. */
    private static QuotaWindow windowWithMargin() throws InterruptedException {
        QuotaWindow window = QuotaWindow.of(Instant.now(), QuotaReservation.WINDOW);
        if (Duration.between(Instant.now(), window.end()).compareTo(WINDOW_MARGIN) < 0) {
            Thread.sleep(Duration.between(Instant.now(), window.end()).plusMillis(200));
            window = QuotaWindow.of(Instant.now(), QuotaReservation.WINDOW);
        }
        return window;
    }

    private static void runTogether(List<TestRequests.Request> requests, Consumer<TestRequests.Request> delivery)
            throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(requests.size())) {
            List<Future<?>> deliveries = requests.stream()
                    .map(request -> executor.submit(() -> delivery.accept(request)))
                    .<Future<?>>map(future -> future)
                    .toList();
            for (Future<?> future : deliveries) {
                future.get();
            }
        }
    }

    private static int totalCalls(List<TestRequests.Request> requests) {
        return requests.stream().mapToInt(request -> PROVIDERS.calls(request.queryId())).sum();
    }

    private static int used(String provider, QuotaWindow window) {
        Map<String, AttributeValue> quota = item(window.key(provider, "default"), "META");
        return Integer.parseInt(attribute(quota, "used").orElse("0"));
    }

    private JsonNode outboxDetail(String eventId) {
        Map<String, AttributeValue> outbox = item("OUTBOX#" + eventId, "META");
        assertThat(outbox).as("outbox %s", eventId).isNotEmpty();
        return objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
    }
}

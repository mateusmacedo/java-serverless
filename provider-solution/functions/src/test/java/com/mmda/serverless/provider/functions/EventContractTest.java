package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.operations.OperationIssue;
import com.mmda.serverless.provider.domain.operations.QueryReconcileRequest;
import com.mmda.serverless.provider.domain.operations.QueryReconciled;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentProfile;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentRequest;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResolved;
import com.mmda.serverless.provider.domain.enrichment.ReuseDecision;
import com.mmda.serverless.provider.domain.insurer.InsurerCallback;
import com.mmda.serverless.provider.domain.insurer.InsurerProtocol;
import com.mmda.serverless.provider.domain.insurer.InsurerQuoteRequest;
import com.mmda.serverless.provider.domain.insurer.InsurerResultRecorded;
import com.mmda.serverless.provider.domain.insurer.Offer;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contrato dos eventos entre as soluções (ADR 0013). Os produzidos são comparados aos exemplos do AsyncAPI
 * da provider, capturados no Floci; os consumidos são lidos dos exemplos do AsyncAPI da quote.
 */
@JsonTest
class EventContractTest {

    private static final Path CONTRACTS = Path.of("..", "..", "contracts");

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void producedEventsSerializeExactlyAsTheDocumentedExamples() throws IOException {
        Map<String, Object> messages = messages("provider-solution/asyncapi.yaml");
        Map<String, Class<?>> details = Map.of(
                "EnrichmentResolved", EnrichmentResolved.class,
                "InsurerResultRecorded", InsurerResultRecorded.class,
                "QueryReconciled", QueryReconciled.class,
                "OperationIssueRaised", OperationIssue.class,
                "OperationIssueUpdated", OperationIssue.class);

        for (Map.Entry<String, Class<?>> detail : details.entrySet()) {
            for (JsonNode example : examples(messages, detail.getKey())) {
                JsonNode documented = example.path("detail");
                Object event = objectMapper.treeToValue(documented, detail.getValue());

                assertThat(objectMapper.readTree(objectMapper.writeValueAsString(event)))
                        .as("%s %s", detail.getKey(), documented.path("eventId").asString())
                        .isEqualTo(documented);
                assertThat(example.path("detail-type").asString()).isEqualTo(detail.getKey());
                assertThat(example.path("source").asString()).isEqualTo("mmda.provider");
            }
        }
    }

    @Test
    void enrichmentResolvedOfTheFoundExampleHasTheDeterministicEventId() throws IOException {
        JsonNode documented = examples(messages("provider-solution/asyncapi.yaml"), "EnrichmentResolved")
                .getFirst().path("detail");
        EnrichmentRequest request = new EnrichmentRequest("request", documented.path("workRef").asString(),
                "org-acme", documented.path("quoteId").asString(), 1, "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                new EnrichmentRequest.Params("vehicle-hatch-2021", "driver-acme-ana"), Instant.now());
        EnrichmentProfile profile = new EnrichmentProfile(
                new EnrichmentProfile.Vehicle("vehicle-hatch-2021", "Volkswagen", "Polo", 2021, "75000.00"),
                new EnrichmentProfile.Driver("driver-acme-ana", 14, 1));

        EnrichmentResolved event = EnrichmentResolved.of(request, Identities.queryId(request.workRef()),
                documented.path("evidenceId").asString(), ReuseDecision.MISS, documented.path("policyVersion").asString(),
                EnrichmentResolved.Outcome.found(profile, documented.path("reusableUntil").asString()),
                documented.path("occurredAt").asString());

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(event))).isEqualTo(documented);
    }

    @Test
    void reusedExamplesCarryTheLeaderEvidenceUnderTheirOwnDeterministicEventId() throws IOException {
        List<JsonNode> examples = examples(messages("provider-solution/asyncapi.yaml"), "EnrichmentResolved");
        JsonNode leader = examples.getFirst().path("detail");
        List<JsonNode> reused = examples.stream()
                .map(example -> example.path("detail"))
                .filter(detail -> !"MISS".equals(detail.path("reuseDecision").asString()))
                .toList();

        assertThat(reused).extracting(detail -> detail.path("reuseDecision").asString())
                .containsExactlyInAnyOrder("JOIN", "HIT");
        for (JsonNode detail : reused) {
            String workRef = detail.path("workRef").asString();
            assertThat(detail.path("workRef").asString()).isEqualTo(Identities.workRef(
                    detail.path("quoteId").asString(), detail.path("version").asInt(), Identities.ENRICHMENT, null));
            assertThat(detail.path("queryId")).isEqualTo(leader.path("queryId"));
            assertThat(detail.path("queryId").asString()).isNotEqualTo(Identities.queryId(workRef));
            assertThat(detail.path("eventId").asString())
                    .isEqualTo(EventIds.enrichmentResolved(detail.path("queryId").asString(), workRef));
            assertThat(detail.path("evidenceId")).isEqualTo(leader.path("evidenceId"));
            assertThat(detail.path("result")).isEqualTo(leader.path("result"));
            assertThat(detail.path("reusableUntil")).isEqualTo(leader.path("reusableUntil"));
            assertThat(detail.path("policyVersion")).isEqualTo(leader.path("policyVersion"));
        }
    }

    @Test
    void insurerResultRecordedOfTheQuotedExampleHasTheDeterministicEventId() throws IOException {
        JsonNode documented = examples(messages("provider-solution/asyncapi.yaml"), "InsurerResultRecorded")
                .getFirst().path("detail");
        InsurerQuoteRequest request = new InsurerQuoteRequest("request", documented.path("workRef").asString(),
                "org-acme", documented.path("quoteId").asString(), 1, "alpha", Map.of(), Map.of(), Instant.now());
        Offer offer = objectMapper.treeToValue(documented.path("offer"), Offer.class);
        String queryId = Identities.queryId(request.workRef());

        InsurerResultRecorded event = InsurerResultRecorded.of(request, queryId, Identities.attemptRef(queryId, 1),
                InsurerResultRecorded.Outcome.quoted(offer), documented.path("occurredAt").asString());

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(event))).isEqualTo(documented);
    }

    /**
     * O exemplo de callback do OpenAPI da provider, classificado como na API de callbacks, produz exatamente o
     * {@code InsurerResultRecorded} documentado da beta, com o nome da seguradora que a quote registra (§3).
     */
    @Test
    @SuppressWarnings("unchecked")
    void callbackExampleOfTheApiBecomesTheDocumentedBetaEvent() throws IOException {
        JsonNode documented = examples(messages("provider-solution/asyncapi.yaml"), "InsurerResultRecorded").stream()
                .map(example -> example.path("detail"))
                .filter(detail -> "beta".equals(detail.path("insurerId").asString())
                        && "QUOTED".equals(detail.path("status").asString()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> openApi;
        try (Reader reader = Files.newBufferedReader(CONTRACTS.resolve("provider-solution")
                .resolve("openapi.yaml"))) {
            openApi = new Yaml().load(reader);
        }
        Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) openApi
                .get("paths")).get("/v1/providers/{provider}/callbacks")).get("post");
        Map<String, Object> examples = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) operation.get("requestBody")).get("content")).get("application/json"))
                .get("examples");
        InsurerCallback callback = objectMapper.convertValue(((Map<String, Object>) examples.get("offer")).get("value"),
                InsurerCallback.class);
        String queryId = documented.path("queryId").asString();
        String quoteId = documented.path("quoteId").asString();
        String workRef = Identities.workRef(quoteId, 1, Identities.INSURER, "beta");

        InsurerResultRecorded event = InsurerResultRecorded.of(
                new InsurerResultRecorded.Origin(workRef, quoteId, 1, "beta"), queryId,
                Identities.attemptRef(queryId, 1),
                InsurerResultRecorded.Outcome.of(InsurerProtocol.classifyCallback(callback.result()),
                        "raw/" + queryId + "/callback-" + callback.externalEventId() + ".json"),
                documented.path("occurredAt").asString());

        assertThat(callback.problems()).isEmpty();
        assertThat(callback.clientReference()).isEqualTo(queryId);
        assertThat(queryId).isEqualTo(Identities.queryId(workRef));
        assertThat(documented.path("eventId").asString()).isEqualTo(EventIds.insurerResultRecorded(queryId));
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(event))).isEqualTo(documented);
        assertThat(event.offer().insurerName()).isEqualTo("Beta Seguros");
    }

    @Test
    void consumedRequestsAreReadFromTheQuoteSolutionExamples() throws IOException {
        Map<String, Object> messages = messages("quote-solution/asyncapi.yaml");
        EventJson json = new EventJson(objectMapper);

        JsonNode enrichmentExample = examples(messages, "EnrichmentRequested").getFirst();
        EnrichmentRequest enrichment = json.detail(objectMapper.writeValueAsString(enrichmentExample),
                EnrichmentRequest.class);
        assertThat(enrichment.eventId()).isEqualTo(enrichmentExample.path("detail").path("eventId").asString());
        assertThat(enrichment.workRef()).isEqualTo(Identities.workRef(enrichment.quoteId(), enrichment.version(),
                Identities.ENRICHMENT, null));
        assertThat(enrichment.provider()).isEqualTo("enrichment-sim");
        assertThat(enrichment.contractVersion()).isEqualTo("1");
        assertThat(enrichment.params().vehicleRef()).isNotBlank();
        assertThat(enrichment.params().principalDriverRef()).isNotBlank();
        assertThat(enrichment.deadlineAt()).isNotNull();

        JsonNode insurerExample = examples(messages, "InsurerQuoteRequested").getFirst();
        InsurerQuoteRequest insurer = json.detail(objectMapper.writeValueAsString(insurerExample),
                InsurerQuoteRequest.class);
        assertThat(insurer.workRef()).isEqualTo(Identities.workRef(insurer.quoteId(), insurer.version(),
                Identities.INSURER, insurer.insurerId()));
        assertThat(insurer.risk()).containsKeys("vehicle", "principalDriver", "usage");
        assertThat(insurer.risk().get("vehicle")).isInstanceOfSatisfying(Map.class,
                vehicle -> assertThat(vehicle).containsKey("id"));
        assertThat(insurer.enrichment()).isNotEmpty();

        JsonNode reconcileExample = examples(messages, "QueryReconcileRequested").getFirst();
        QueryReconcileRequest reconcile = json.detail(objectMapper.writeValueAsString(reconcileExample),
                QueryReconcileRequest.class);
        assertThat(reconcile.eventId()).isEqualTo(reconcileExample.path("detail").path("eventId").asString());
        assertThat(reconcile.operationId()).isNotBlank();
        assertThat(reconcile.organizationId()).isNotBlank();
        assertThat(reconcile.queryId()).matches("^q_[0-9a-f]{32}$");
        assertThat(reconcile.requestedBy()).isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> messages(String file) throws IOException {
        try (Reader reader = Files.newBufferedReader(CONTRACTS.resolve(file))) {
            Map<String, Object> document = new Yaml().load(reader);
            return (Map<String, Object>) ((Map<String, Object>) document.get("components")).get("messages");
        }
    }

    @SuppressWarnings("unchecked")
    private List<JsonNode> examples(Map<String, Object> messages, String name) {
        Map<String, Object> message = (Map<String, Object>) messages.get(name);
        assertThat(message).as("message %s", name).isNotNull();
        return ((List<Map<String, Object>>) message.get("examples")).stream()
                .map(example -> (JsonNode) objectMapper.valueToTree(example.get("payload")))
                .toList();
    }
}

package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.Insurers;
import com.mmda.serverless.quote.domain.Operations;
import com.mmda.serverless.quote.domain.Outcome;
import com.mmda.serverless.quote.domain.OutcomeReason;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Usage;
import com.mmda.serverless.quote.domain.WorkRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Cada evento que a quote produz e os exemplos conferem com {@code contracts/quote-solution/asyncapi.yaml}, que
 * declara o que ela consome. Uma regra com filtro errado entregaria evento a quem não deveria (ADR 0013); o
 * contrato é o que os consumidores leem.
 */
class QuoteEventsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-11T18:00:00Z");
    private static final String QUOTE_ID = "5b0b6c1e-5f1d-4a8e-9a39-2f1f5d6e7c11";

    private static JsonNode asyncApi;

    @BeforeAll
    static void load() throws IOException {
        Object document = new Yaml().load(Files.readString(Path.of("../../contracts/quote-solution/asyncapi.yaml")));
        asyncApi = MAPPER.valueToTree(document);
    }

    @Test
    void quoteAcceptedFollowsTheContract() {
        assertValid("QuoteAcceptedDetail",
                QuoteEvents.quoteAccepted("8a1f2c4e-3b5d-4e6f-9a7b-1c2d3e4f5a6b", NOW, QUOTE_ID, 1, "org-acme"));
    }

    @Test
    void enrichmentRequestedFollowsTheContract() {
        WorkRef workRef = WorkRef.of(QUOTE_ID, 1, WorkRef.Kind.ENRICHMENT, null);

        assertValid("EnrichmentRequestedDetail", QuoteEvents.enrichmentRequested(NOW, workRef, "org-acme", QUOTE_ID, 1,
                QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false, "2012-03-15"), NOW.plusSeconds(20)));
    }

    @Test
    void insurerQuoteRequestedFollowsTheContractForEveryInsurer() {
        Map<String, Object> enrichment = new LinkedHashMap<>();
        enrichment.put("evidenceId", "ev_a8e049d6bebff920a7afb8424c8410df");
        enrichment.put("queryId", "q_6d1f1a54bd4666ed04f96f9a63b420ea");
        enrichment.put("reuseDecision", "MISS");
        enrichment.put("resultType", "FOUND");
        enrichment.put("result", Map.of("vehicle", Map.of("year", 2021)));

        for (String insurerId : Insurers.all()) {
            WorkRef workRef = WorkRef.of(QUOTE_ID, 1, WorkRef.Kind.INSURER, insurerId);
            assertValid("InsurerQuoteRequestedDetail", QuoteEvents.insurerQuoteRequested(NOW, workRef, "org-acme",
                    QUOTE_ID, 1, insurerId, QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false, null), enrichment,
                    NOW.plusSeconds(20)));
        }
    }

    @Test
    void quoteCompletedFollowsTheContractWithAndWithoutReason() {
        assertValid("QuoteCompletedDetail", QuoteEvents.quoteCompleted(NOW, QUOTE_ID, 1, Outcome.COMPLETED, null, 2));
        assertValid("QuoteCompletedDetail", QuoteEvents.quoteCompleted(NOW, QUOTE_ID, 1, Outcome.NO_OFFER,
                OutcomeReason.NO_APPLICABLE_INSURER, 0));
    }

    @Test
    void offerSelectedAndQueryReconcileRequestedOfTheApiFollowTheContract() {
        String selectionId = QuoteKeys.selectionId(QUOTE_ID, 1);
        assertValid("OfferSelectedDetail", QuoteEvents.offerSelected(NOW, QUOTE_ID, 1,
                "of_0f1e2d3c4b5a69788796a5b4c3d2e1f0", selectionId));
        assertValid("QueryReconcileRequestedDetail", QuoteEvents.queryReconcileRequested(
                "0b9f6a52-3c1e-4d7a-9f2b-6e8d4c1a7b35", NOW, Operations.newOperationId(), "org-acme",
                "q_099d4987d3e2f2e6656e9285ac76a83e", "Consulta UNKNOWN; conferir com o fornecedor.", "olga"));
    }

    @Test
    void documentedExamplesFollowTheirOwnSchemas() {
        Map<String, String> schemaByMessage = Map.of(
                "QuoteAccepted", "QuoteAcceptedDetail",
                "EnrichmentRequested", "EnrichmentRequestedDetail",
                "InsurerQuoteRequested", "InsurerQuoteRequestedDetail",
                "QuoteCompleted", "QuoteCompletedDetail",
                "QueryReconcileRequested", "QueryReconcileRequestedDetail");
        int examples = 0;
        for (Map.Entry<String, String> entry : schemaByMessage.entrySet()) {
            for (JsonNode example : asyncApi.at("/components/messages/" + entry.getKey() + "/examples")) {
                JsonNode payload = example.get("payload");
                assertThat(payload.get("source").asText()).isEqualTo(QuoteEvents.SOURCE);
                assertThat(payload.get("detail-type").asText()).isEqualTo(entry.getKey());
                assertThat(validate(schema(entry.getValue()), payload.get("detail"), "detail")).isEmpty();
                examples++;
            }
        }
        assertThat(examples).isEqualTo(5);
    }

    /**
     * O que a quote consome está declarado no documento dela: operações {@code receive} nas filas {@code results} e
     * {@code issues}, com mensagens que existem no AsyncAPI da provider. São os eventos que o {@code resume} e o
     * {@code issue-projector} tratam.
     */
    @Test
    void consumedProviderEventsAreReceiveOperationsWithTheProviderMessages() throws IOException {
        JsonNode provider = MAPPER.valueToTree(new Yaml().load(
                Files.readString(Path.of("../../contracts/provider-solution/asyncapi.yaml"))));
        Map<String, String> queueByEvent = new TreeMap<>();
        for (JsonNode operation : asyncApi.path("operations")) {
            if (!"receive".equals(operation.path("action").asText())) {
                continue;
            }
            JsonNode channel = asyncApi.at(operation.at("/channel/$ref").asText().substring(1));
            for (JsonNode message : operation.path("messages")) {
                String reference = asyncApi.at(message.path("$ref").asText().substring(1)).path("$ref").asText();
                assertThat(reference).startsWith("../provider-solution/asyncapi.yaml#/components/messages/");
                JsonNode providerMessage = provider.at(reference.substring(reference.indexOf('#') + 1));
                assertThat(providerMessage.path("name").isTextual()).as(reference).isTrue();
                queueByEvent.put(providerMessage.path("name").asText(), channel.path("address").asText());
            }
        }

        assertThat(queueByEvent).containsExactlyInAnyOrderEntriesOf(Map.of(
                QuoteEvents.ENRICHMENT_RESOLVED, "quote-solution-{stage}-results",
                QuoteEvents.INSURER_RESULT_RECORDED, "quote-solution-{stage}-results",
                QuoteEvents.QUERY_RECONCILED, "quote-solution-{stage}-issues",
                QuoteEvents.OPERATION_ISSUE_RAISED, "quote-solution-{stage}-issues",
                QuoteEvents.OPERATION_ISSUE_UPDATED, "quote-solution-{stage}-issues"));
    }

    @Test
    void insurerRequestExampleCarriesTheProviderIdentifiersOfItsEnrichment() {
        JsonNode enrichmentRequest = asyncApi.at("/components/messages/EnrichmentRequested/examples/0/payload/detail");
        JsonNode insurerRequest = asyncApi.at("/components/messages/InsurerQuoteRequested/examples/0/payload/detail");
        String queryId = "q_" + Hashes.sha256Hex(enrichmentRequest.get("workRef").asText()).substring(0, 32);

        assertThat(insurerRequest.get("quoteId")).isEqualTo(enrichmentRequest.get("quoteId"));
        assertThat(insurerRequest.at("/enrichment/queryId").asText()).isEqualTo(queryId);
        assertThat(insurerRequest.at("/enrichment/evidenceId").asText())
                .isEqualTo("ev_" + Hashes.sha256Hex(queryId + "|1").substring(0, 32));
        assertThat(insurerRequest.at("/enrichment/result/vehicle/year").asInt())
                .isEqualTo(insurerRequest.at("/risk/vehicle/year").asInt());
    }

    private static void assertValid(String schemaName, Map<String, Object> detail) {
        JsonNode json = MAPPER.valueToTree(detail);
        assertThat(validate(schema(schemaName), json, "detail")).as(schemaName).isEmpty();
    }

    private static JsonNode schema(String name) {
        return asyncApi.at("/components/schemas/" + name);
    }

    /** Subconjunto do JSON Schema usado no documento: tipos, obrigatórios, propriedades fechadas, const e enum. */
    private static List<String> validate(JsonNode schema, JsonNode value, String path) {
        List<String> errors = new ArrayList<>();
        if (schema.has("$ref")) {
            return validate(asyncApi.at(schema.get("$ref").asText().substring(1)), value, path);
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            errors.add(path + " should be " + schema.get("const"));
        }
        if (schema.has("enum")) {
            boolean listed = false;
            for (JsonNode option : schema.get("enum")) {
                listed |= option.equals(value);
            }
            if (!listed) {
                errors.add(path + " = " + value + " is not in the enum");
            }
        }
        if (schema.has("type") && !typeMatches(schema.get("type"), value)) {
            errors.add(path + " should be " + schema.get("type") + " but is " + value.getNodeType());
        }
        if (schema.has("pattern") && value.isTextual() && !value.asText().matches(schema.get("pattern").asText())) {
            errors.add(path + " does not match " + schema.get("pattern"));
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asText())) {
                    errors.add(path + "." + required.asText() + " is required");
                }
            }
            JsonNode properties = schema.path("properties");
            for (Iterator<Map.Entry<String, JsonNode>> fields = value.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (properties.has(field.getKey())) {
                    errors.addAll(validate(properties.get(field.getKey()), field.getValue(), path + "." + field.getKey()));
                } else if (schema.path("additionalProperties").isBoolean() && !schema.get("additionalProperties").asBoolean()) {
                    errors.add(path + "." + field.getKey() + " is not in the contract");
                }
            }
        }
        if (value.isArray() && schema.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                errors.addAll(validate(schema.get("items"), value.get(i), path + "[" + i + "]"));
            }
        }
        return errors;
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode option : type) {
                if (typeMatches(option, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}

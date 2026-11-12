package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentProfile;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentRequest;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResolved;
import com.mmda.serverless.provider.domain.enrichment.ReuseDecision;
import com.mmda.serverless.provider.domain.insurer.InsurerProtocol;
import com.mmda.serverless.provider.domain.insurer.InsurerQuoteRequest;
import com.mmda.serverless.provider.domain.insurer.InsurerReply;
import com.mmda.serverless.provider.domain.insurer.InsurerResultRecorded;
import com.mmda.serverless.provider.domain.insurer.MissingField;
import com.mmda.serverless.provider.domain.insurer.Offer;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Os eventos que a provider produz em cada desfecho, serializados como no outbox, conferem com os schemas do
 * {@code docs/events/provider-solution.asyncapi.yaml}: obrigatórios, enums de {@code errorClass} e
 * {@code status}, padrões de identificadores e de valores. O teste de exemplos só cobre os desfechos
 * documentados; este cobre também os caminhos sem exemplo.
 */
@JsonTest
class ProducedEventSchemaTest {

    /** Códigos que só a seguradora produz; os demais podem ser o {@code errorClass} do enriquecimento. */
    private static final Set<String> INSURER_ONLY = Set.of(ErrorClasses.UNSUPPORTED_INSURER);
    private static final String QUOTE_ID = "5b0b6c1e-5f1d-4a8e-9a39-2f1f5d6e7c11";

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode asyncApi;
    private final String occurredAt = Timestamps.format(Instant.parse("2026-11-11T18:00:05Z"));

    @BeforeEach
    void load() throws IOException {
        try (Reader reader = Files.newBufferedReader(Path.of("..", "..", "docs", "events",
                "provider-solution.asyncapi.yaml"))) {
            asyncApi = objectMapper.valueToTree(new Yaml().load(reader));
        }
    }

    @Test
    void enrichmentResolvedOfEveryResultTypeAndErrorClassFollowsTheSchema() throws IllegalAccessException {
        EnrichmentRequest request = new EnrichmentRequest(UUID.randomUUID().toString(),
                Identities.workRef(QUOTE_ID, 1, Identities.ENRICHMENT, null), "org-acme", QUOTE_ID, 1,
                "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                new EnrichmentRequest.Params("vehicle-hatch-2021", "driver-acme-ana"), Instant.now());
        String queryId = Identities.queryId(request.workRef());
        String reusableUntil = Timestamps.format(Instant.parse("2026-11-11T18:15:05Z"));
        List<EnrichmentResolved.Outcome> outcomes = new ArrayList<>(List.of(
                EnrichmentResolved.Outcome.found(new EnrichmentProfile(
                        new EnrichmentProfile.Vehicle("vehicle-hatch-2021", "Volkswagen", "Polo", 2021, "75000.00"),
                        new EnrichmentProfile.Driver("driver-acme-ana", 14, 1)), reusableUntil),
                EnrichmentResolved.Outcome.notFound(List.of("vehicleRef", "principalDriverRef"), reusableUntil)));
        for (String errorClass : errorClasses()) {
            if (!INSURER_ONLY.contains(errorClass)) {
                outcomes.add(EnrichmentResolved.Outcome.failed(errorClass));
            }
        }

        for (ReuseDecision decision : ReuseDecision.values()) {
            for (EnrichmentResolved.Outcome outcome : outcomes) {
                EnrichmentResolved event = EnrichmentResolved.of(request, queryId, Identities.evidenceId(queryId, 1),
                        decision, "reuse-v1", outcome, occurredAt);

                assertThat(validate(schema("EnrichmentResolvedDetail"), objectMapper.valueToTree(event), "detail"))
                        .as("%s %s %s", decision, outcome.resultType(), outcome.errorClass())
                        .isEmpty();
            }
        }
    }

    @Test
    void enrichmentResolvedRequiresThePolicyVersionOfTheProvider() {
        EnrichmentRequest request = new EnrichmentRequest(UUID.randomUUID().toString(),
                Identities.workRef(QUOTE_ID, 1, Identities.ENRICHMENT, null), "org-acme", QUOTE_ID, 1,
                "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                new EnrichmentRequest.Params("vehicle-hatch-2021", "driver-acme-ana"), Instant.now());
        String queryId = Identities.queryId(request.workRef());
        JsonNode event = objectMapper.valueToTree(EnrichmentResolved.of(request, queryId,
                Identities.evidenceId(queryId, 1), ReuseDecision.MISS, "reuse-v1",
                EnrichmentResolved.Outcome.failed(ErrorClasses.PROVIDER_UNAVAILABLE), occurredAt));
        ((ObjectNode) event).remove("policyVersion");

        assertThat(validate(schema("EnrichmentResolvedDetail"), event, "detail"))
                .containsExactly("detail.policyVersion is required");
    }

    @Test
    void reusePolicyVersionFollowsTheDocumentedFormat() {
        assertThat(schema("EnrichmentResolvedDetail").at("/allOf/1/properties/policyVersion/pattern").asString())
                .isEqualTo(EnrichmentResolved.POLICY_VERSION.pattern());
        assertThat(new ReuseProperties(30, 3600, "reuse-v1.2").policyVersion()).isEqualTo("reuse-v1.2");
        assertThatThrownBy(() -> new ReuseProperties(30, 3600, "reuse v1"))
                .as("a configured version outside the pattern would publish events outside the AsyncAPI")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insurerResultRecordedOfEveryStatusFollowsTheSchema() {
        InsurerQuoteRequest request = new InsurerQuoteRequest(UUID.randomUUID().toString(),
                Identities.workRef(QUOTE_ID, 1, Identities.INSURER, "alpha"), "org-acme", QUOTE_ID, 1, "alpha",
                Map.of("usage", "PERSONAL"), Map.of(), Instant.now());
        String queryId = Identities.queryId(request.workRef());
        String attemptRef = Identities.attemptRef(queryId, 1);
        Offer offer = new Offer("ALPHA-1", "Alpha Seguros", "2450.90", "BRL",
                List.of(new Offer.Installment(1, "2450.90"), new Offer.Installment(10, "245.09")),
                List.of(new Offer.Coverage("COLLISION", "Colisão", "90000.00"),
                        new Offer.Coverage("GLASS", "Vidros", null)),
                List.of(new Offer.Deductible("COLLISION", "3500.00")),
                "2026-11-11T18:30:05.000Z", "raw/q.json");
        List<InsurerCase> cases = List.of(
                new InsurerCase(InsurerResultRecorded.Outcome.quoted(offer), attemptRef),
                new InsurerCase(InsurerResultRecorded.Outcome.declined("RISK_NOT_ACCEPTED"), attemptRef),
                new InsurerCase(InsurerResultRecorded.Outcome.needsInformation(
                        InsurerProtocol.NEEDS_INFORMATION_REASON,
                        List.of(new MissingField("risk.principalDriver.licenseDate", "MISSING_LICENSE_DATE"))),
                        attemptRef),
                new InsurerCase(InsurerResultRecorded.Outcome.unavailable(ErrorClasses.PROVIDER_UNAVAILABLE),
                        attemptRef),
                new InsurerCase(InsurerResultRecorded.Outcome.unavailable(ErrorClasses.UNSUPPORTED_INSURER), null));

        for (InsurerCase insurerCase : cases) {
            InsurerResultRecorded event = InsurerResultRecorded.of(request, queryId, insurerCase.attemptRef(),
                    insurerCase.outcome(), occurredAt);

            assertThat(validate(schema("InsurerResultRecordedDetail"), objectMapper.valueToTree(event), "detail"))
                    .as("%s %s", insurerCase.outcome().status(), insurerCase.outcome().reasonCode())
                    .isEmpty();
        }
    }

    /** O resultado de cada callback da beta, classificado como na API de callbacks, segue o schema. */
    @Test
    void insurerResultRecordedFromEveryCallbackResultFollowsTheSchema() {
        String workRef = Identities.workRef(QUOTE_ID, 1, Identities.INSURER, "beta");
        String queryId = Identities.queryId(workRef);
        InsurerReply offer = new InsurerReply(null, "OFFER", "BETA-095D0858", "Beta Seguros",
                new InsurerReply.Premium("2481.46", "BRL"),
                List.of(new InsurerReply.PaymentOption(1, "2481.46"), new InsurerReply.PaymentOption(12, "206.79")),
                List.of(new InsurerReply.CoverageItem("COLLISION", "Colisão, incêndio e roubo", "75000.00")),
                List.of(new InsurerReply.DeductibleItem("COLLISION", "3000.00")),
                "2026-11-12T02:09:50.786Z", null, null, null, null);
        List<InsurerReply> results = List.of(offer,
                new InsurerReply(null, "DECLINED", null, null, null, null, null, null, null, "RISK_NOT_ACCEPTED", null,
                        null, null),
                new InsurerReply(null, "PENDING_INFORMATION", null, null, null, null, null, null, null, null,
                        List.of(new InsurerReply.PendingField("risk.principalDriver.licenseDate", "MISSING_LICENSE_DATE")),
                        null, null),
                new InsurerReply(null, "OFFER", null, "Beta Seguros", null, null, null, null, null, null, null, null,
                        null));

        for (InsurerReply result : results) {
            InsurerResultRecorded event = InsurerResultRecorded.of(
                    new InsurerResultRecorded.Origin(workRef, QUOTE_ID, 1, "beta"), queryId,
                    Identities.attemptRef(queryId, 1),
                    InsurerResultRecorded.Outcome.of(InsurerProtocol.classifyCallback(result),
                            "raw/" + queryId + "/callback-bev-1.json"),
                    occurredAt);

            assertThat(validate(schema("InsurerResultRecordedDetail"), objectMapper.valueToTree(event), "detail"))
                    .as("callback %s", result.outcome())
                    .isEmpty();
        }
    }

    /** {@code attemptRef} nulo quando a provider concluiu sem chamar a seguradora. */
    private record InsurerCase(InsurerResultRecorded.Outcome outcome, String attemptRef) {
    }

    private static List<String> errorClasses() throws IllegalAccessException {
        List<String> codes = new ArrayList<>();
        for (Field field : ErrorClasses.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                codes.add((String) field.get(null));
            }
        }
        assertThat(codes).isNotEmpty();
        return codes;
    }

    private JsonNode schema(String name) {
        JsonNode schema = asyncApi.at("/components/schemas/" + name);
        assertThat(schema.isObject()).as("schema %s", name).isTrue();
        return schema;
    }

    /** Subconjunto do JSON Schema usado no documento, com {@code allOf} e {@code $ref} locais. */
    private List<String> validate(JsonNode schema, JsonNode value, String path) {
        List<String> errors = new ArrayList<>();
        if (schema.has("$ref")) {
            return validate(asyncApi.at(schema.get("$ref").asString().substring(1)), value, path);
        }
        for (JsonNode part : schema.path("allOf")) {
            errors.addAll(validate(part, value, path));
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
        if (schema.has("type") && !typeMatches(schema.get("type").asString(), value)) {
            errors.add(path + " should be " + schema.get("type") + " but is " + value.getNodeType());
        }
        if (value.isString()) {
            String text = value.asString();
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asString()).matcher(text).find()) {
                errors.add(path + " = " + text + " does not match " + schema.get("pattern"));
            }
            if (!formatMatches(schema.path("format").asString(), text)) {
                errors.add(path + " = " + text + " is not a " + schema.get("format"));
            }
        }
        if (schema.has("minimum") && value.isNumber() && value.asInt() < schema.get("minimum").asInt()) {
            errors.add(path + " is below " + schema.get("minimum"));
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asString())) {
                    errors.add(path + "." + required.asString() + " is required");
                }
            }
            JsonNode properties = schema.path("properties");
            for (Map.Entry<String, JsonNode> field : value.properties()) {
                if (properties.has(field.getKey())) {
                    errors.addAll(validate(properties.get(field.getKey()), field.getValue(),
                            path + "." + field.getKey()));
                }
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + " has fewer than " + schema.get("minItems") + " items");
            }
            for (int i = 0; schema.has("items") && i < value.size(); i++) {
                errors.addAll(validate(schema.get("items"), value.get(i), path + "[" + i + "]"));
            }
        }
        return errors;
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> false;
        };
    }

    private static boolean formatMatches(String format, String text) {
        try {
            switch (format) {
                case "date-time" -> Instant.parse(text);
                case "uuid" -> UUID.fromString(text);
                default -> {
                    return true;
                }
            }
            return true;
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            return false;
        }
    }
}

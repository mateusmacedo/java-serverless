package com.mmda.serverless.quote.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.Operations;
import com.mmda.serverless.quote.domain.Outcome;
import com.mmda.serverless.quote.domain.OutcomeReason;
import com.mmda.serverless.quote.domain.Permission;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteActions;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.Usage;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T22, lado do backend: as fixtures de {@code contracts/fixtures}, que o frontend usa como mocks, seguem
 * {@code contracts/openapi.yaml} em cada requisição e resposta, cobrem os casos que o contrato compartilhado pede
 * (progresso, oferta parcial, recusa, indisponibilidade, pendência, expiração, conflito e os erros padrão) e os
 * enums do contrato são os mesmos do backend.
 */
class ContractFixturesTest {

    private static final Path CONTRACTS = Path.of("../../contracts").toAbsolutePath().normalize();
    private static final String SPECIFICATION = CONTRACTS.resolve("openapi.yaml").toUri().toString();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Fixtures que o contrato compartilhado pede, mais as das rotas do M5. */
    private static final Set<String> REQUIRED = Set.of("quote-progress.json", "quote-partial-offer.json",
            "quote-declined.json", "quote-unavailable.json", "quote-needs-information.json",
            "quote-partial-needs-information.json", "offer-expiration.json", "conflicts.json", "errors.json",
            "selection.json", "quotes-list.json", "catalog-pages.json", "operations.json", "me.json");

    private static OpenApiInteractionValidator validator;
    private static OpenAPI openApi;
    private static Map<String, JsonNode> fixtures;

    @BeforeAll
    static void load() throws IOException {
        validator = OpenApiInteractionValidator.createForSpecificationUrl(SPECIFICATION)
                .withBasePathOverride("/")
                .build();
        openApi = new OpenAPIV3Parser().read(SPECIFICATION);
        fixtures = new TreeMap<>();
        try (Stream<Path> files = Files.list(CONTRACTS.resolve("fixtures"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonNode document = MAPPER.readTree(file.toFile());
                if (document.has("exchanges")) {
                    fixtures.put(file.getFileName().toString(), document);
                }
            }
        }
    }

    @Test
    void t22_everyFixtureExchangeFollowsTheContract() {
        List<String> errors = new ArrayList<>();
        int exchanges = 0;
        for (Map.Entry<String, JsonNode> fixture : fixtures.entrySet()) {
            assertThat(fixture.getValue().path("description").asText()).as(fixture.getKey()).isNotBlank();
            for (JsonNode exchange : fixture.getValue().get("exchanges")) {
                Request request = request(exchange.get("request"));
                // NOTE: o 400 de validação nasce de um body inválido de propósito; só a resposta é conferida nele
                ValidationReport report = exchange.path("invalidRequest").asBoolean(false)
                        ? validator.validateResponse(request.getPath(), request.getMethod(),
                                response(exchange.get("response")))
                        : validator.validate(request, response(exchange.get("response")));
                report.getMessages().stream()
                        .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                        .forEach(message -> errors.add(fixture.getKey() + " / " + exchange.get("name").asText() + ": "
                                + message.getKey() + " " + message.getMessage()));
                exchanges++;
            }
        }
        assertThat(errors).isEmpty();
        assertThat(exchanges).isGreaterThanOrEqualTo(50);
    }

    @Test
    void t22_fixturesCoverWhatTheSharedContractAsksFor() {
        assertThat(fixtures.keySet()).containsAll(REQUIRED);
        Set<String> phases = new HashSet<>();
        Set<String> outcomes = new HashSet<>();
        Set<String> insurerStatuses = new HashSet<>();
        Set<String> unavailability = new HashSet<>();
        Set<String> conflictCodes = new HashSet<>();
        Set<Integer> statuses = new HashSet<>();
        Set<String> operationStatuses = new HashSet<>();
        boolean partialWithInsurerPendency = false;
        boolean reconcilableIssue = false;
        boolean selectionDuringQuoting = false;
        for (JsonNode fixture : fixtures.values()) {
            for (JsonNode exchange : fixture.get("exchanges")) {
                JsonNode body = exchange.at("/response/body");
                int status = exchange.at("/response/status").asInt();
                statuses.add(status);
                if (status == 409) {
                    conflictCodes.add(body.get("code").asText());
                }
                if (body.has("insurerResults")) {
                    phases.add(body.get("phase").asText());
                    if (!body.get("outcome").isNull()) {
                        outcomes.add(body.get("outcome").asText());
                    }
                    body.get("insurerResults").forEach(result -> insurerStatuses.add(result.get("status").asText()));
                    partialWithInsurerPendency |= "PARTIAL".equals(body.get("outcome").asText())
                            && body.get("missingFields").isEmpty()
                            && hasInsurerPendency(body.get("insurerResults"));
                    selectionDuringQuoting |= "QUOTING".equals(body.get("phase").asText())
                            && body.get("allowedActions").toString().contains("SELECT_OFFER");
                }
                if (body.has("selectable")) {
                    unavailability.add(body.path("unavailabilityReason").asText());
                }
                body.path("items").forEach(item -> {
                    if (item.has("unavailabilityReason") && !item.get("unavailabilityReason").isNull()) {
                        unavailability.add(item.get("unavailabilityReason").asText());
                    }
                });
                for (JsonNode item : body.path("items")) {
                    reconcilableIssue |= item.path("allowedActions").toString().contains("RECONCILE");
                }
                if (body.has("operationId") && body.has("status")) {
                    operationStatuses.add(body.get("status").asText());
                }
            }
        }
        assertThat(phases).as("progresso").containsAll(names(Phase.values()));
        assertThat(outcomes).containsAll(names(Outcome.values()));
        assertThat(insurerStatuses).as("recusa, indisponibilidade e pendência")
                .containsAll(names(InsurerStatus.values()));
        assertThat(unavailability).as("expiração").containsAll(names(QuoteActions.UnavailabilityReason.values()));
        assertThat(conflictCodes).as("conflito").containsAll(schemaEnum("ConflictError", "code"));
        assertThat(statuses).as("erros padrão").contains(400, 401, 403, 404, 409, 429, 503);
        assertThat(operationStatuses).contains("QUEUED", "SUCCEEDED", "FAILED");
        assertThat(partialWithInsurerPendency).as("PARTIAL com pendência em insurerResults[].missingFields").isTrue();
        assertThat(reconcilableIssue).isTrue();
        assertThat(selectionDuringQuoting).isTrue();
    }

    @Test
    void t22_enumsOfTheContractAreTheOnesTheBackendUses() {
        assertThat(schemaEnum("Phase")).containsExactlyElementsOf(names(Phase.values()));
        assertThat(schemaEnum("Outcome")).containsExactlyElementsOf(names(Outcome.values()));
        assertThat(schemaEnum("OutcomeReason")).containsExactlyElementsOf(names(OutcomeReason.values()));
        assertThat(schemaEnum("InsurerStatus")).containsExactlyElementsOf(names(InsurerStatus.values()));
        assertThat(schemaEnum("Usage")).containsExactlyElementsOf(names(Usage.values()));
        assertThat(schemaEnum("Permission"))
                .containsExactlyElementsOf(Arrays.stream(Permission.values()).map(Permission::code).toList());
        assertThat(schemaEnum("AllowedAction")).containsExactly(QuoteActions.CREATE_VERSION, QuoteActions.SELECT_OFFER);
        assertThat(schemaEnum("UnavailabilityReason"))
                .containsExactlyElementsOf(names(QuoteActions.UnavailabilityReason.values()));
        assertThat(schemaEnum("IssueType")).containsExactlyElementsOf(names(Operations.IssueType.values()));
        assertThat(schemaEnum("IssueStatus")).containsExactlyElementsOf(names(QuoteIssues.Status.values()));
        assertThat(schemaEnum("IssueAction")).containsExactly(Operations.RECONCILE);
        assertThat(schemaEnum("OperationStatus"))
                .containsExactlyElementsOf(names(Operations.OperationStatus.values()));
        assertThat(((Schema<?>) openApi.getComponents().getSchemas().get("Selection").getProperties().get("status"))
                .getConst())
                .isEqualTo(QuoteViews.SELECTION_STATUS);
    }

    private static boolean hasInsurerPendency(JsonNode results) {
        for (JsonNode result : results) {
            if (!result.get("missingFields").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Request request(JsonNode request) {
        String[] target = request.get("path").asText().split("\\?", 2);
        SimpleRequest.Builder builder = new SimpleRequest.Builder(request.get("method").asText(), target[0])
                .withAuthorization("Bearer fixture");
        if (target.length > 1) {
            for (String parameter : target[1].split("&")) {
                String[] pair = parameter.split("=", 2);
                builder.withQueryParam(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        request.path("headers").properties().forEach(header -> builder.withHeader(header.getKey(),
                header.getValue().asText()));
        if (request.has("body")) {
            builder.withContentType("application/json").withBody(request.get("body").toString());
        }
        return builder.build();
    }

    private static SimpleResponse response(JsonNode response) {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.get("status").asInt())
                .withContentType("application/json")
                .withBody(response.get("body").toString());
        response.path("headers").properties().forEach(header -> builder.withHeader(header.getKey(),
                header.getValue().asText()));
        return builder.build();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static List<String> schemaEnum(String name) {
        return enumOf(openApi.getComponents().getSchemas().get(name));
    }

    private static List<String> schemaEnum(String name, String property) {
        return enumOf((Schema<?>) openApi.getComponents().getSchemas().get(name).getProperties().get(property));
    }

    private static List<String> enumOf(Schema<?> schema) {
        return schema.getEnum().stream().map(String::valueOf).toList();
    }
}

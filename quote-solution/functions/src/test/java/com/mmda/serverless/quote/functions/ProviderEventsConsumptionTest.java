package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.EnrichmentOutcome;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.Insurers;
import com.mmda.serverless.quote.domain.MissingField;
import com.mmda.serverless.quote.domain.MissingFieldLabels;
import com.mmda.serverless.quote.domain.QuoteEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * A quote consome os eventos da provider-solution como o {@code docs/events/provider-solution.asyncapi.yaml}
 * os documenta; a provider confere esses exemplos contra o próprio código. Cada campo que o {@code resume} e
 * a consolidação leem precisa estar no exemplo, com o tipo que a quote espera.
 */
class ProviderEventsConsumptionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORK_REF = "^wr_[0-9a-f]{32}$";
    private static final Set<InsurerStatus> REPORTED = Set.of(InsurerStatus.QUOTED, InsurerStatus.DECLINED,
            InsurerStatus.NEEDS_INFORMATION, InsurerStatus.UNAVAILABLE);

    private static JsonNode asyncApi;

    @BeforeAll
    static void load() throws IOException {
        Object document = new Yaml().load(Files.readString(Path.of("../../docs/events/provider-solution.asyncapi.yaml")));
        asyncApi = MAPPER.valueToTree(document);
    }

    @Test
    void enrichmentResolvedExamplesCarryWhatTheResumeAndTheSelectionRead() {
        Set<EnrichmentOutcome.ResultType> seen = new HashSet<>();
        for (JsonNode detail : details(QuoteEvents.ENRICHMENT_RESOLVED)) {
            assertCommonFields(detail);
            assertThat(detail.path("evidenceId").asText()).isNotBlank();
            EnrichmentOutcome.ResultType resultType = EnrichmentOutcome.ResultType.valueOf(detail.path("resultType").asText());
            seen.add(resultType);
            switch (resultType) {
                case FOUND -> assertThat(detail.at("/result/vehicle/year").canConvertToInt())
                        .as("sel-1 reads the enriched vehicle year").isTrue();
                case NOT_FOUND -> {
                    List<String> refs = new ArrayList<>();
                    detail.path("notFoundRefs").forEach(ref -> refs.add(ref.asText()));
                    assertThat(refs).isNotEmpty();
                    for (MissingField field : EnrichmentOutcome.notFound(refs).missingFields()) {
                        assertThat(MissingFieldLabels.labelOf(field.path())).as(field.path()).isNotEqualTo(field.path());
                    }
                }
                case FAILED -> assertThat(detail.path("errorClass").asText()).isNotBlank();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(EnrichmentOutcome.ResultType.values());
    }

    @Test
    void insurerResultRecordedExamplesCarryWhatTheResumeReads() {
        int quoted = 0;
        for (JsonNode detail : details(QuoteEvents.INSURER_RESULT_RECORDED)) {
            assertCommonFields(detail);
            InsurerStatus status = InsurerStatus.valueOf(detail.path("status").asText());
            assertThat(REPORTED).contains(status);
            String insurerId = detail.path("insurerId").asText();
            assertThat(Insurers.nameOf(insurerId)).as("insurerId %s is in the quote registry", insurerId).isPresent();
            if (status != InsurerStatus.QUOTED) {
                assertThat(detail.path("reasonCode").asText()).isNotBlank();
                continue;
            }
            JsonNode offer = detail.path("offer");
            assertThat(offer.path("insurerName").asText())
                    .as("the offer and the insurer result show the same name")
                    .isEqualTo(Insurers.nameOf(insurerId).orElseThrow());
            assertThat(offer.path("premiumTotal").asText()).matches("^\\d+(\\.\\d+)?$");
            assertThat(offer.path("currency").asText()).isNotBlank();
            assertThat(Instant.parse(offer.path("validUntil").asText())).isNotNull();
            assertThat(offer.path("installments").isArray()).isTrue();
            assertThat(offer.path("coverages").isArray()).isTrue();
            assertThat(offer.path("deductibles").isArray()).isTrue();
            offer.path("coverages").forEach(coverage -> assertThat(coverage.path("label").asText())
                    .as("OfferCoverage.label is required by the product API").isNotBlank());
            quoted++;
        }
        assertThat(quoted).isPositive();
    }

    private static void assertCommonFields(JsonNode detail) {
        assertThat(UUID.fromString(detail.path("eventId").asText())).isNotNull();
        assertThat(detail.path("workRef").asText()).matches(WORK_REF);
        assertThat(detail.path("queryId").asText()).isNotBlank();
        assertThat(detail.path("version").canConvertToInt()).isTrue();
    }

    private static List<JsonNode> details(String detailType) {
        List<JsonNode> details = new ArrayList<>();
        for (JsonNode example : asyncApi.at("/components/messages/" + detailType + "/examples")) {
            JsonNode payload = example.path("payload");
            assertThat(payload.path("source").asText()).isEqualTo(QuoteEvents.PROVIDER_SOURCE);
            assertThat(payload.path("detail-type").asText()).isEqualTo(detailType);
            details.add(payload.path("detail"));
        }
        assertThat(details).as("examples of %s", detailType).isNotEmpty();
        return details;
    }
}

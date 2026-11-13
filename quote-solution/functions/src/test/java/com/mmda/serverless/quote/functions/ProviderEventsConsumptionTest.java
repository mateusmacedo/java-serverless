package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.EnrichmentOutcome;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.Insurers;
import com.mmda.serverless.quote.domain.MissingField;
import com.mmda.serverless.quote.domain.MissingFieldLabels;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.ReuseDecision;
import com.mmda.serverless.quote.domain.WorkRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * A quote consome os eventos da provider-solution como o
 * {@code contracts/provider-solution/asyncapi.yaml}
 * os documenta, e a provider confere esses exemplos contra o próprio código.
 * Cada exemplo passa pelo
 * {@code resume}, com DynamoDB simulado, até a evidência usada pela versão ou o
 * resultado e a oferta da
 * seguradora, alpha ou beta.
 */
class ProviderEventsConsumptionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORK_REF = "^wr_[0-9a-f]{32}$";
    private static final Set<InsurerStatus> REPORTED = Set.of(InsurerStatus.QUOTED, InsurerStatus.DECLINED,
            InsurerStatus.NEEDS_INFORMATION, InsurerStatus.UNAVAILABLE);

    private static JsonNode asyncApi;

    @BeforeAll
    static void load() throws IOException {
        Object document = new Yaml().load(Files.readString(Path.of("../../contracts/provider-solution/asyncapi.yaml")));
        asyncApi = MAPPER.valueToTree(document);
    }

    @Test
    void enrichmentResolvedExamplesCarryWhatTheResumeAndTheSelectionRead() {
        Set<EnrichmentOutcome.ResultType> seen = new HashSet<>();
        Set<ReuseDecision> decisions = EnumSet.noneOf(ReuseDecision.class);
        for (JsonNode detail : details(QuoteEvents.ENRICHMENT_RESOLVED)) {
            assertCommonFields(detail);
            assertThat(detail.path("evidenceId").asText()).isNotBlank();
            decisions.add(ReuseDecision.valueOf(detail.path("reuseDecision").asText()));
            assertThat(detail.path("policyVersion").isTextual()).as("policyVersion of the provider reuse policy")
                    .isTrue();
            assertThat(detail.path("policyVersion").asText()).isNotBlank();
            EnrichmentOutcome.ResultType resultType = EnrichmentOutcome.ResultType
                    .valueOf(detail.path("resultType").asText());
            seen.add(resultType);
            switch (resultType) {
                case FOUND -> assertThat(detail.at("/result/vehicle/year").canConvertToInt())
                        .as("sel-1 reads the enriched vehicle year").isTrue();
                case NOT_FOUND -> {
                    List<String> refs = new ArrayList<>();
                    detail.path("notFoundRefs").forEach(ref -> refs.add(ref.asText()));
                    assertThat(refs).isNotEmpty();
                    for (MissingField field : EnrichmentOutcome.notFound(refs).missingFields()) {
                        assertThat(MissingFieldLabels.labelOf(field.path())).as(field.path())
                                .isNotEqualTo(field.path());
                    }
                }
                case FAILED -> assertThat(detail.path("errorClass").asText()).isNotBlank();
            }
        }
        assertThat(seen).containsExactlyInAnyOrder(EnrichmentOutcome.ResultType.values());
        assertThat(decisions).as("HIT, JOIN and MISS reach the quote")
                .containsExactlyInAnyOrder(ReuseDecision.values());
    }

    @Test
    void enrichmentResolvedExamplesBecomeTheEvidenceUsedByTheVersion() {
        for (JsonNode example : asyncApi.at("/components/messages/" + QuoteEvents.ENRICHMENT_RESOLVED + "/examples")) {
            JsonNode detail = example.at("/payload/detail");
            WorkRef workRef = new WorkRef(detail.path("workRef").asText());
            ResumeProbe probe = new ResumeProbe(workRef, detail.path("quoteId").asText(),
                    detail.path("version").asInt(),
                    ResumeProbe.NOW.plusSeconds(20));

            assertThat(probe.resume(example.path("payload").toString()))
                    .as("example %s", example.path("name").asText()).isEqualTo(ResultResumer.Disposition.RESUMED);

            Map<String, AttributeValue> evidence = probe.put(QuoteKeys.TYPE_EVIDENCE);
            assertThat(evidence.get(QuoteKeys.SK).s())
                    .isEqualTo(QuoteKeys.evidenceSk(detail.path("version").asInt(), workRef));
            for (String field : List.of("evidenceId", "queryId", "reuseDecision", "resultType", "policyVersion")) {
                assertThat(evidence).as("evidence used").containsKey(field);
                assertThat(evidence.get(field).s()).as(field).isEqualTo(detail.path(field).asText());
            }
            JsonNode summary = ResumeProbe.summary(evidence);
            assertThat(present(summary, "result")).isEqualTo(present(detail, "result"));
            assertThat(present(summary, "notFoundRefs")).isEqualTo(present(detail, "notFoundRefs"));
            assertThat(probe.resumePayload().get("reuseDecision").asText())
                    .isEqualTo(detail.path("reuseDecision").asText());
        }
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

    /**
     * A alpha responde na chamada e a beta por callback, mas as duas publicam o
     * mesmo contrato: cada exemplo passa
     * pelo {@code resume} até o {@code RESULT} da sua seguradora e, com
     * {@code QUOTED}, até a {@code OFFER}.
     */
    @Test
    void insurerResultRecordedExamplesOfEveryInsurerBecomeTheirResultAndOffer() {
        Set<String> insurers = new HashSet<>();
        for (JsonNode example : asyncApi
                .at("/components/messages/" + QuoteEvents.INSURER_RESULT_RECORDED + "/examples")) {
            JsonNode detail = example.at("/payload/detail");
            String name = example.path("name").asText();
            String insurerId = detail.path("insurerId").asText();
            insurers.add(insurerId);
            String quoteId = detail.path("quoteId").asText();
            int version = detail.path("version").asInt();
            WorkRef workRef = new WorkRef(detail.path("workRef").asText());
            ResumeProbe probe = new ResumeProbe(workRef, WorkRef.Kind.INSURER, insurerId, quoteId, version,
                    ResumeProbe.NOW.plusSeconds(20));

            assertThat(probe.resume(example.path("payload").toString()))
                    .as("example %s", name).isEqualTo(ResultResumer.Disposition.RESUMED);

            String status = detail.path("status").asText();
            Map<String, AttributeValue> values = probe.update(QuoteKeys.quotePk(quoteId),
                    QuoteKeys.resultSk(version, insurerId)).expressionAttributeValues();
            assertThat(values.get(":status").s()).as(name).isEqualTo(status);
            assertThat(probe.resumePayload().get("status").asText()).isEqualTo(status);
            assertThat(probe.put(QuoteKeys.TYPE_CONSUMED).get(QuoteKeys.PK).s())
                    .isEqualTo(QuoteKeys.consumedPk(detail.path("eventId").asText()));
            for (MissingField field : readMissingFields(values.get(":missing").s())) {
                assertThat(MissingFieldLabels.labelOf(field.path())).as(field.path()).isNotEqualTo(field.path());
            }
            if (!InsurerStatus.QUOTED.name().equals(status)) {
                assertThat(probe.puts(QuoteKeys.TYPE_OFFER)).as(name).isEmpty();
                continue;
            }
            Map<String, AttributeValue> offer = probe.put(QuoteKeys.TYPE_OFFER);
            assertThat(offer.get("offerId").s()).isEqualTo(QuoteKeys.offerId(workRef));
            assertThat(offer.get(QuoteKeys.SK).s()).isEqualTo(QuoteKeys.offerSk(version, QuoteKeys.offerId(workRef)));
            assertThat(offer.get("insurerId").s()).isEqualTo(insurerId);
            assertThat(offer.get("insurerName").s()).isEqualTo(Insurers.nameOf(insurerId).orElseThrow());
            assertThat(offer.get("queryId").s()).isEqualTo(detail.path("queryId").asText());
            assertThat(probe.resumePayload().get("offerId").asText()).isEqualTo(QuoteKeys.offerId(workRef));
        }
        assertThat(insurers).as("alpha and beta publish the same contract")
                .containsExactlyInAnyOrderElementsOf(Insurers.all());
    }

    /**
     * Os eventos operacionais da provider chegam pela fila {@code issues}: cada
     * exemplo passa pelo
     * {@code issue-projector}, com a tabela simulada, até o {@code ISSUE#} ou o
     * {@code OPERATION#}, com o
     * {@code CONSUMED#} na mesma transação. Só o par de uma ocorrência grava o
     * {@code QUERYREF#}; o do
     * {@code QueryReconciled} é o do pedido e não prova que a consulta é da
     * organização.
     */
    @Test
    void operationalExamplesBecomeTheirIssueOrOperationAndOnlyAnIssueMakesItsQueryKnown() {
        int projected = 0;
        for (String detailType : List.of(QuoteEvents.QUERY_RECONCILED, QuoteEvents.OPERATION_ISSUE_RAISED,
                QuoteEvents.OPERATION_ISSUE_UPDATED)) {
            for (JsonNode example : asyncApi.at("/components/messages/" + detailType + "/examples")) {
                JsonNode detail = example.at("/payload/detail");
                String name = detailType + " " + example.path("name").asText();
                DynamoDbClient dynamoDb = mock(DynamoDbClient.class);
                when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
                when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                        .thenReturn(TransactWriteItemsResponse.builder().build());
                IssueProjector projector = new IssueProjector(
                        new QuoteTable(dynamoDb, new OutboxPublishingTest.TestConfig(), MAPPER), MAPPER,
                        Clock.fixed(ResumeProbe.NOW, ZoneOffset.UTC));

                assertThat(projector.handle(example.path("payload").toString())).as(name)
                        .isEqualTo(IssueProjector.Disposition.PROJECTED);

                ArgumentCaptor<TransactWriteItemsRequest> transaction = ArgumentCaptor
                        .forClass(TransactWriteItemsRequest.class);
                verify(dynamoDb).transactWriteItems(transaction.capture());
                List<TransactWriteItem> writes = transaction.getValue().transactItems();
                boolean operation = QuoteEvents.QUERY_RECONCILED.equals(detailType);
                Update projection = writes.getFirst().update();
                assertThat(projection.key().get(QuoteKeys.PK).s()).as(name).isEqualTo(operation
                        ? QuoteKeys.operationPk(detail.path("operationId").asText())
                        : QuoteKeys.issuePk(detail.path("issueId").asText()));
                assertThat(projection.expressionAttributeValues().get(":status").s()).isEqualTo(
                        detail.path("status").asText());
                if (!operation) {
                    assertThat(projection.expressionAttributeValues().get(":issueType").s()).as("the event type")
                            .isEqualTo(detail.path("type").asText());
                }
                assertThat(writes.get(1).put().item().get(QuoteKeys.PK).s())
                        .isEqualTo(QuoteKeys.consumedPk(detail.path("eventId").asText()));
                List<String> queryRefs = writes.stream().filter(write -> write.update() != null)
                        .map(write -> write.update().key().get(QuoteKeys.PK).s())
                        .filter(pk -> pk.equals(QuoteKeys.queryRefPk(detail.path("queryId").asText())))
                        .toList();
                assertThat(queryRefs).as(name).hasSize(operation ? 0 : 1);
                projected++;
            }
        }
        assertThat(projected).isGreaterThanOrEqualTo(3);
    }

    private static List<MissingField> readMissingFields(String json) {
        try {
            return List.of(MAPPER.readValue(json, MissingField[].class));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static JsonNode present(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
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

package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.ReuseDecision;
import com.mmda.serverless.quote.domain.WorkRef;
import com.mmda.serverless.quote.functions.QuoteFixtures.EvidenceRef;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Evidência usada pela versão a partir do {@code EnrichmentResolved} (§4 e §8 do desenho): {@code HIT},
 * {@code JOIN} e {@code MISS} gravam a mesma referência, com o {@code policyVersion}, e um evento fora do
 * contrato do AsyncAPI da provider volta à fila em vez de virar evidência.
 */
class EnrichmentEvidenceRecordingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUOTE_ID = "7c4f3a52-9a0e-4c1b-8d55-0f6e2b1d9a10";
    private static final WorkRef WORK_REF = WorkRef.of(QUOTE_ID, 1, WorkRef.Kind.ENRICHMENT, null);
    private static final EvidenceRef LEADER = EvidenceRef.shared(
            WorkRef.of("0d6b8f1e-2c4a-4e7b-9f30-5a1c7e9b2d44", 1, WorkRef.Kind.ENRICHMENT, null).value(), "MISS");

    @ParameterizedTest
    @EnumSource(ReuseDecision.class)
    void everyReuseDecisionRecordsTheEvidenceReferenceAndThePolicyVersion(ReuseDecision decision) {
        ResumeProbe probe = waitingProbe();

        assertThat(probe.resume(found(LEADER.withReuseDecision(decision.name()))))
                .isEqualTo(ResultResumer.Disposition.RESUMED);

        Map<String, AttributeValue> evidence = probe.put(QuoteKeys.TYPE_EVIDENCE);
        assertThat(evidence.get(QuoteKeys.SK).s()).isEqualTo(QuoteKeys.evidenceSk(1, WORK_REF));
        assertThat(evidence.get("evidenceId").s()).isEqualTo(LEADER.evidenceId());
        assertThat(evidence.get("queryId").s()).isEqualTo(LEADER.queryId());
        assertThat(evidence.get("reuseDecision").s()).isEqualTo(decision.name());
        assertThat(evidence.get("resultType").s()).isEqualTo("FOUND");
        assertThat(evidence.get("policyVersion").s()).isEqualTo(EvidenceRef.POLICY_VERSION);
        assertThat(ResumeProbe.summary(evidence).at("/result/vehicle/year").asInt()).isEqualTo(2021);
        JsonNode payload = probe.resumePayload();
        assertThat(payload.get("resultType").asText()).isEqualTo("FOUND");
        assertThat(payload.get("reuseDecision").asText()).isEqualTo(decision.name());
        assertThat(payload.get("evidenceId").asText()).isEqualTo(LEADER.evidenceId());
    }

    @Test
    void sharedNotFoundKeepsTheDeclaredReferences() {
        ResumeProbe probe = waitingProbe();

        probe.resume(QuoteFixtures.enrichmentResult(WORK_REF.value(), QUOTE_ID, UUID.randomUUID().toString(),
                LEADER.withReuseDecision("JOIN"), "NOT_FOUND", 0, List.of("vehicleRef", "principalDriverRef")));

        Map<String, AttributeValue> evidence = probe.put(QuoteKeys.TYPE_EVIDENCE);
        assertThat(evidence.get("resultType").s()).isEqualTo("NOT_FOUND");
        assertThat(evidence.get("reuseDecision").s()).isEqualTo("JOIN");
        assertThat(ResumeProbe.summary(evidence).get("notFoundRefs"))
                .extracting(JsonNode::asText).containsExactly("vehicleRef", "principalDriverRef");
    }

    @ParameterizedTest
    @MethodSource("eventsOutsideTheContract")
    void eventOutsideTheContractGoesBackToTheQueueWithoutEvidence(Consumer<ObjectNode> change) {
        ResumeProbe probe = waitingProbe();
        String body = withDetail(found(LEADER), change);

        LambdaEvents.BatchResponse response = new ResumeHandler(probe.resumer()).handleRequest(QuoteFixtures.sqs(body), null);

        assertThat(response.batchItemFailures()).hasSize(1);
        assertThat(probe.writes()).isEmpty();
    }

    /** A reentrega chega depois do prazo do waiter (visibilidade de 90 s), e o evento não vira evidência tardia. */
    @ParameterizedTest
    @MethodSource("eventsOutsideTheContract")
    void eventOutsideTheContractGoesBackToTheQueueEvenAfterTheDeadline(Consumer<ObjectNode> change) {
        ResumeProbe probe = new ResumeProbe(WORK_REF, QUOTE_ID, 1, ResumeProbe.NOW.minusSeconds(1));
        String body = withDetail(found(LEADER), change);

        LambdaEvents.BatchResponse response = new ResumeHandler(probe.resumer()).handleRequest(QuoteFixtures.sqs(body), null);

        assertThat(response.batchItemFailures()).hasSize(1);
        assertThat(probe.writes()).isEmpty();
    }

    static Stream<Named<Consumer<ObjectNode>>> eventsOutsideTheContract() {
        return Stream.of(
                Named.of("unknown reuseDecision", detail -> detail.put("reuseDecision", "STALE")),
                Named.of("without reuseDecision", detail -> detail.remove("reuseDecision")),
                Named.of("without evidenceId", detail -> detail.remove("evidenceId")),
                Named.of("without queryId", detail -> detail.remove("queryId")),
                Named.of("without policyVersion", detail -> detail.remove("policyVersion")),
                Named.of("empty policyVersion", detail -> detail.put("policyVersion", "")),
                Named.of("policyVersion outside the pattern", detail -> detail.put("policyVersion", "reuse v1")),
                Named.of("policyVersion that is not text", detail -> detail.putObject("policyVersion")),
                Named.of("queryId outside the pattern", detail -> detail.put("queryId", "q_enrichment")),
                Named.of("evidenceId outside the pattern", detail -> detail.put("evidenceId", "ev_1")),
                Named.of("HIT of a FAILED result", detail -> {
                    detail.remove("result");
                    detail.remove("reusableUntil");
                    detail.put("resultType", "FAILED");
                    detail.put("errorClass", "PROVIDER_UNAVAILABLE");
                    detail.put("reuseDecision", "HIT");
                }),
                Named.of("FOUND without result", detail -> detail.remove("result")),
                Named.of("NOT_FOUND without notFoundRefs", detail -> {
                    detail.remove("result");
                    detail.put("resultType", "NOT_FOUND");
                }),
                Named.of("NOT_FOUND with empty notFoundRefs", detail -> {
                    detail.remove("result");
                    detail.put("resultType", "NOT_FOUND");
                    detail.putArray("notFoundRefs");
                }),
                Named.of("NOT_FOUND with a reference the request does not have", detail -> {
                    detail.remove("result");
                    detail.put("resultType", "NOT_FOUND");
                    detail.putArray("notFoundRefs").add("vehicleRef").add("customerRef");
                }),
                Named.of("NOT_FOUND with notFoundRefs that is not a list", detail -> {
                    detail.remove("result");
                    detail.put("resultType", "NOT_FOUND");
                    detail.putObject("notFoundRefs").put("ref", "vehicleRef");
                }),
                Named.of("NOT_FOUND with a reference that is not text", detail -> {
                    detail.remove("result");
                    detail.put("resultType", "NOT_FOUND");
                    detail.putArray("notFoundRefs").addArray().add("vehicleRef");
                }));
    }

    @Test
    void lateEnrichmentKeepsTheEvidenceReferenceForEvaluation() {
        ResumeProbe probe = new ResumeProbe(WORK_REF, QUOTE_ID, 1, ResumeProbe.NOW.minusSeconds(1));

        assertThat(probe.resume(found(LEADER.withReuseDecision("JOIN")))).isEqualTo(ResultResumer.Disposition.LATE);

        Map<String, AttributeValue> late = probe.put(QuoteKeys.TYPE_LATE);
        assertThat(late.get(QuoteKeys.SK).s()).isEqualTo(QuoteKeys.lateSk(1, "ENRICHMENT", LEADER.queryId()));
        assertThat(late.get("reason").s()).isEqualTo("LATE");
        assertThat(late.get("status").s()).isEqualTo("FOUND");
        assertThat(late.get("queryRef").s()).isEqualTo(LEADER.queryId());
        assertThat(late.get("evidenceId").s()).isEqualTo(LEADER.evidenceId());
        assertThat(late.get("reuseDecision").s()).isEqualTo("JOIN");
        assertThat(late.get("policyVersion").s()).isEqualTo(EvidenceRef.POLICY_VERSION);
        assertThat(probe.puts(QuoteKeys.TYPE_EVIDENCE)).isEmpty();
    }

    private static ResumeProbe waitingProbe() {
        return new ResumeProbe(WORK_REF, QUOTE_ID, 1, ResumeProbe.NOW.plusSeconds(20));
    }

    private static String found(EvidenceRef evidence) {
        return QuoteFixtures.enrichmentResult(WORK_REF.value(), QUOTE_ID, UUID.randomUUID().toString(), evidence,
                "FOUND", 2021, List.of());
    }

    private static String withDetail(String body, Consumer<ObjectNode> change) {
        try {
            ObjectNode envelope = (ObjectNode) MAPPER.readTree(body);
            change.accept((ObjectNode) envelope.get("detail"));
            return envelope.toString();
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Usage;
import com.mmda.serverless.quote.domain.WorkRef;
import com.mmda.serverless.quote.functions.DispatchHandler.DispatchRequest;
import com.mmda.serverless.quote.functions.QuoteFixtures.EvidenceRef;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Passos, {@code dispatch}, {@code resume}, relay e reconciler contra DynamoDB, EventBridge, SQS, Secrets
 * Manager e Step Functions reais do Floci, com task tokens reais. A máquina da quote não é implantada: os
 * passos são chamados na ordem em que ela os chama, e o deploy de verificação cobre a máquina.
 */
@QuarkusTest
@WithTestResource(FlociResource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuoteFunctionsFlociIT {

    @Inject
    WorkflowStepsHandler steps;

    @Inject
    DispatchHandler dispatch;

    @Inject
    ResumeHandler resume;

    @Inject
    ResultResumer resumer;

    @Inject
    OutboxRelayHandler relay;

    @Inject
    ReconcilerHandler reconciler;

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    SfnClient sfn;

    @Inject
    ObjectMapper objectMapper;

    // WHY: os padrões só existem para a classe validar na inicialização dos testes do surefire, sem o Floci
    @ConfigProperty(name = "it.capture-queue-url", defaultValue = "unset")
    String captureQueueUrl;

    @ConfigProperty(name = "it.token-queue-url", defaultValue = "unset")
    String tokenQueueUrl;

    @ConfigProperty(name = "it.token-machine-arn", defaultValue = "unset")
    String tokenMachineArn;

    @ConfigProperty(name = "quote.state-machine-arn")
    String flowMachineArn;

    private SqsClient sqs;
    private final Map<String, String> tokensByExecution = new HashMap<>();

    @BeforeAll
    void openSqs() {
        sqs = SqsClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
    }

    @AfterAll
    void closeSqs() {
        sqs.close();
    }

    @Test
    void t01_singleSyncInsurerQuoteCompletes() throws Exception {
        String quoteId = newQuote(Usage.COMMERCIAL, 2021, false, "2012-03-15");

        assertThat(step("load-snapshot", quoteId).get("organizationId")).isEqualTo("org-acme");
        assertThat(version(quoteId).get("phase").s()).isEqualTo("VALIDATING");
        assertThat(step("evaluate-eligibility", quoteId).get("decision")).isEqualTo("ELIGIBLE");
        assertThat(version(quoteId).get("phase").s()).isEqualTo("ENRICHING");

        resolveEnrichment(quoteId, 2021);
        assertThat(step("select-insurers", quoteId).get("insurers")).isEqualTo(List.of("alpha"));
        assertThat(version(quoteId).get("phase").s()).isEqualTo("QUOTING");
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("PENDING");

        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.INSURER, "alpha");
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("PROCESSING");
        JsonNode request = outboxDetail(workRef.requestEventId());
        assertThat(request.get("workRef").asText()).isEqualTo(workRef.value());
        assertThat(request.at("/risk/vehicle/year").asInt()).isEqualTo(2021);
        assertThat(request.at("/enrichment/evidenceId").asText()).isEqualTo(
                EvidenceRef.miss(WorkRef.of(quoteId, 1, WorkRef.Kind.ENRICHMENT, null).value()).evidenceId());
        assertThat(request.toString()).doesNotContain(task.token());

        LambdaEvents.BatchResponse response = resume.handleRequest(
                QuoteFixtures.sqs(QuoteFixtures.insurerResult(workRef.value(), quoteId, "QUOTED", UUID.randomUUID().toString())),
                null);
        assertThat(response.batchItemFailures()).isEmpty();
        assertThat(succeededOutput(task).get("status").asText()).isEqualTo("QUOTED");
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("QUOTED");
        assertThat(waiter(workRef).get("status").s()).isEqualTo("DONE");
        assertThat(offers(quoteId)).hasSize(1);

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("COMPLETED");
        assertThat(consolidation.get("outcomeReason")).isNull();
        Map<String, AttributeValue> version = version(quoteId);
        assertThat(version.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(version).doesNotContainKey("gsi2pk");
        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(meta.get("outcome").s()).isEqualTo("COMPLETED");
        assertThat(meta.get("offerCount").n()).isEqualTo("1");
        JsonNode completed = outboxDetail(QuoteEvents.completedEventId(quoteId, 1));
        assertThat(completed.get("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("offerCount").asInt()).isEqualTo(1);

        assertThat(step("consolidate", quoteId).get("outcome")).isEqualTo("COMPLETED");
        assertThat(version(quoteId).get("revision").n()).isEqualTo(version.get("revision").n());
    }

    @Test
    void t23_ineligibleRiskEndsWithoutExternalQuery() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 2017, true, "2010-01-01");

        step("load-snapshot", quoteId);
        assertThat(step("evaluate-eligibility", quoteId).get("decision")).isEqualTo("INELIGIBLE");
        Map<String, Object> consolidation = step("consolidate", quoteId);

        assertThat(consolidation.get("outcome")).isEqualTo("INELIGIBLE");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("SALVAGE_VEHICLE");
        assertThat(version(quoteId).get("eligibilityRuleVersion").s()).isEqualTo("elig-1");
        assertNoExternalQuery(quoteId);
    }

    @Test
    void t24_incompleteInputEndsWithMissingFields() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 2020, false, null);

        step("load-snapshot", quoteId);
        assertThat(step("evaluate-eligibility", quoteId).get("decision")).isEqualTo("NEEDS_INFORMATION");
        Map<String, Object> consolidation = step("consolidate", quoteId);

        assertThat(consolidation.get("outcome")).isEqualTo("NEEDS_INFORMATION");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("MISSING_LICENSE_DATE");
        JsonNode missing = objectMapper.readTree(version(quoteId).get("missingFields").s());
        assertThat(missing.get(0).get("path").asText()).isEqualTo("risk.principalDriverRef");
        assertThat(missing.get(0).get("reasonCode").asText()).isEqualTo("MISSING_LICENSE_DATE");
        assertNoExternalQuery(quoteId);
    }

    @Test
    void dispatchRetryOnlyReplacesTheTokenAndANewTaskReceivesAnArrivedResult() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.INSURER, "alpha");
        Task first = newTask();
        Task retry = newTask();

        dispatch.handleRequest(insurerRequest(quoteId, first), null);
        String firstCiphertext = waiter(workRef).get("tokenCiphertext").s();
        dispatch.handleRequest(insurerRequest(quoteId, retry), null);

        assertThat(waiter(workRef).get("tokenCiphertext").s()).isNotEqualTo(firstCiphertext);
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).hasSize(1);

        resume.handleRequest(QuoteFixtures.sqs(
                QuoteFixtures.insurerResult(workRef.value(), quoteId, "DECLINED", UUID.randomUUID().toString())), null);
        assertThat(succeededOutput(retry).get("status").asText()).isEqualTo("DECLINED");
        assertThat(execution(first).status()).isEqualTo(ExecutionStatus.RUNNING);

        Task late = newTask();
        assertThat(dispatch.handleRequest(insurerRequest(quoteId, late), null).get("status")).isEqualTo("RESUMED");
        assertThat(succeededOutput(late).get("status").asText()).isEqualTo("DECLINED");
        sfn.stopExecution(builder -> builder.executionArn(first.executionArn()));
    }

    @Test
    void repeatedResultIsDiscardedAndAnotherResultBecomesDuplicateEvidence() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.INSURER, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        String eventId = UUID.randomUUID().toString();
        String quoted = QuoteFixtures.insurerResult(workRef.value(), quoteId, "QUOTED", eventId);

        assertThat(resumer.handle(quoted)).isEqualTo(ResultResumer.Disposition.RESUMED);
        assertThat(resumer.handle(quoted)).isEqualTo(ResultResumer.Disposition.DISCARDED);
        assertThat(resumer.handle(QuoteFixtures.insurerResult(workRef.value(), quoteId, "QUOTED",
                UUID.randomUUID().toString()))).isEqualTo(ResultResumer.Disposition.DUPLICATE);

        assertThat(offers(quoteId)).hasSize(1);
        assertThat(lateItems(quoteId)).singleElement()
                .satisfies(late -> assertThat(late.get("reason").s()).isEqualTo("DUPLICATE"));
        assertThat(item(QuoteKeys.consumedPk(eventId), "META")).isNotNull();
    }

    @Test
    void timedOutInsurerKeepsTheLateResultAsEvidenceAndEndsAsTechnicalFailure() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.INSURER, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);

        Map<String, Object> recorded = steps.handleRequest(new StepRequest("record-insurer-outcome", quoteId, 1, "alpha",
                "TIMED_OUT", objectMapper.readTree("{\"Error\":\"States.Timeout\",\"Cause\":\"\"}")), null);
        assertThat(recorded.get("status")).isEqualTo("TIMED_OUT");
        assertThat(waiter(workRef).get("status").s()).isEqualTo("EXPIRED");

        assertThat(resumer.handle(QuoteFixtures.insurerResult(workRef.value(), quoteId, "QUOTED",
                UUID.randomUUID().toString()))).isEqualTo(ResultResumer.Disposition.LATE);
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("TIMED_OUT");
        assertThat(offers(quoteId)).isEmpty();
        assertThat(lateItems(quoteId)).singleElement()
                .satisfies(late -> assertThat(late.get("reason").s()).isEqualTo("LATE"));

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("TECHNICAL_FAILURE");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("INSURER_TECHNICAL_FAILURE");
        sfn.stopExecution(builder -> builder.executionArn(task.executionArn()));
    }

    @Test
    void t04_reusedEnrichmentFollowsTheSameFlowAsAMissAndKeepsTheEvidenceReference() throws Exception {
        EvidenceRef leader = EvidenceRef.shared(
                WorkRef.of(UUID.randomUUID().toString(), 1, WorkRef.Kind.ENRICHMENT, null).value(), "MISS");
        for (String decision : List.of("HIT", "JOIN")) {
            String quoteId = eligibleQuote(Usage.COMMERCIAL);
            WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.ENRICHMENT, null);
            Task enrichment = newTask();
            dispatch.handleRequest(enrichmentRequest(quoteId, enrichment), null);

            assertThat(resumer.handle(QuoteFixtures.enrichmentResult(workRef.value(), quoteId,
                    UUID.randomUUID().toString(), leader.withReuseDecision(decision), "FOUND", 2021, List.of())))
                    .isEqualTo(ResultResumer.Disposition.RESUMED);
            JsonNode resumed = succeededOutput(enrichment);
            assertThat(resumed.get("resultType").asText()).isEqualTo("FOUND");
            assertThat(resumed.get("reuseDecision").asText()).isEqualTo(decision);
            Map<String, AttributeValue> used = evidence(quoteId, workRef);
            assertThat(used.get("reuseDecision").s()).as(decision).isEqualTo(decision);
            assertThat(used.get("evidenceId").s()).isEqualTo(leader.evidenceId());
            assertThat(used.get("queryId").s()).isEqualTo(leader.queryId());
            assertThat(used.get("policyVersion").s()).isEqualTo(EvidenceRef.POLICY_VERSION);

            assertThat(step("select-insurers", quoteId).get("insurers")).isEqualTo(List.of("alpha"));
            Task insurer = newTask();
            dispatch.handleRequest(insurerRequest(quoteId, insurer), null);
            WorkRef insurerRef = WorkRef.of(quoteId, 1, WorkRef.Kind.INSURER, "alpha");
            JsonNode request = outboxDetail(insurerRef.requestEventId());
            assertThat(request.at("/enrichment/evidenceId").asText()).isEqualTo(leader.evidenceId());
            assertThat(request.at("/enrichment/queryId").asText()).isEqualTo(leader.queryId());
            assertThat(request.at("/enrichment/reuseDecision").asText()).isEqualTo(decision);
            resume.handleRequest(QuoteFixtures.sqs(QuoteFixtures.insurerResult(insurerRef.value(), quoteId, "QUOTED",
                    UUID.randomUUID().toString())), null);
            assertThat(succeededOutput(insurer).get("status").asText()).isEqualTo("QUOTED");

            Map<String, Object> consolidation = step("consolidate", quoteId);
            assertThat(consolidation.get("outcome")).as(decision).isEqualTo("COMPLETED");
            assertThat(consolidation.get("outcomeReason")).isNull();
            assertThat(offers(quoteId)).hasSize(1);
            assertThat(outboxOf(quoteId, QuoteEvents.ENRICHMENT_REQUESTED)).hasSize(1);
            assertThat(evidence(quoteId, workRef)).as("the evidence used stays as recorded").isEqualTo(used);
        }
    }

    @Test
    void t25_requiredEnrichmentNotFoundEndsAsNeedsInformationWithoutInsurers() throws Exception {
        String quoteId = eligibleQuote(Usage.PERSONAL);
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.ENRICHMENT, null);
        Task task = newTask();
        dispatch.handleRequest(enrichmentRequest(quoteId, task), null);

        assertThat(resumer.handle(QuoteFixtures.enrichmentResult(workRef.value(), quoteId, UUID.randomUUID().toString(),
                EvidenceRef.miss(workRef.value()), "NOT_FOUND", 0, List.of("vehicleRef", "principalDriverRef"))))
                .isEqualTo(ResultResumer.Disposition.RESUMED);
        assertThat(succeededOutput(task).get("resultType").asText()).isEqualTo("NOT_FOUND");
        Map<String, AttributeValue> used = evidence(quoteId, workRef);
        assertThat(used.get("resultType").s()).isEqualTo("NOT_FOUND");
        assertThat(used.get("evidenceId").s()).isEqualTo(EvidenceRef.miss(workRef.value()).evidenceId());

        assertThat(step("select-insurers", quoteId).get("insurers")).as("no insurer even if the step runs").isEqualTo(List.of());
        Map<String, Object> consolidation = step("consolidate", quoteId);

        assertThat(consolidation.get("outcome")).isEqualTo("NEEDS_INFORMATION");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("ENRICHMENT_NOT_FOUND");
        JsonNode missing = objectMapper.readTree(version(quoteId).get("missingFields").s());
        assertThat(missing).extracting(field -> field.get("path").asText())
                .containsExactly("risk.vehicleRef", "risk.principalDriverRef");
        assertThat(missing).extracting(field -> field.get("reasonCode").asText()).containsOnly("ENRICHMENT_NOT_FOUND");
        assertThat(outboxDetail(QuoteEvents.completedEventId(quoteId, 1)).get("outcomeReason").asText())
                .isEqualTo("ENRICHMENT_NOT_FOUND");
        assertNoInsurerQueried(quoteId);
    }

    @Test
    void t27_requiredEnrichmentFailureEndsAsTechnicalFailureWithoutInsurers() throws Exception {
        String reported = eligibleQuote(Usage.PERSONAL);
        WorkRef reportedRef = WorkRef.of(reported, 1, WorkRef.Kind.ENRICHMENT, null);
        Task task = newTask();
        dispatch.handleRequest(enrichmentRequest(reported, task), null);
        resumer.handle(QuoteFixtures.enrichmentResult(reportedRef.value(), reported, "FAILED", 0,
                UUID.randomUUID().toString()));
        assertThat(succeededOutput(task).get("resultType").asText()).isEqualTo("FAILED");
        JsonNode summary = objectMapper.readTree(evidence(reported, reportedRef).get("summary").s());
        assertThat(summary.path("result").isObject()).as("no profile is invented").isFalse();
        assertThat(summary.path("errorClass").asText()).isEqualTo("PROVIDER_UNAVAILABLE");

        String timedOut = eligibleQuote(Usage.PERSONAL);
        WorkRef timedOutRef = WorkRef.of(timedOut, 1, WorkRef.Kind.ENRICHMENT, null);
        Map<String, Object> recorded = steps.handleRequest(new StepRequest("record-enrichment-outcome", timedOut, 1,
                null, null, objectMapper.readTree("{\"Error\":\"States.Timeout\"}")), null);
        assertThat(recorded.get("resultType")).isEqualTo("FAILED");
        assertThat(waiter(timedOutRef).get("status").s()).isEqualTo("EXPIRED");
        assertThat(evidence(timedOut, timedOutRef)).isNull();

        for (String quoteId : List.of(reported, timedOut)) {
            assertThat(step("select-insurers", quoteId).get("insurers")).as("no insurer even if the step runs")
                    .isEqualTo(List.of());
            Map<String, Object> consolidation = step("consolidate", quoteId);
            assertThat(consolidation.get("outcome")).isEqualTo("TECHNICAL_FAILURE");
            assertThat(consolidation.get("outcomeReason")).isEqualTo("ENRICHMENT_FAILED");
            assertThat(version(quoteId).get("missingFields").s()).isEqualTo("[]");
            assertNoInsurerQueried(quoteId);
        }
    }

    @Test
    void t19_crashBetweenCommitAndPublishIsRecoveredWithoutDuplicateExecution() throws Exception {
        String quoteId = UUID.randomUUID().toString();
        QuoteFixtures.Created created = QuoteFixtures.createQuote(dynamoDb, quoteId,
                QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false, "2012-03-15"), Instant.now().minusSeconds(120));
        String executionArn = flowMachineArn.replace(":stateMachine:", ":execution:") + ":q-" + quoteId + "-v1";

        Map<String, Object> report = reconciler.handleRequest(Map.of("source", "test"), null);

        assertThat((Integer) report.get("republished")).isGreaterThanOrEqualTo(2);
        assertThat(version(quoteId).get("executionArn").s()).isEqualTo(executionArn);
        assertThat(sfn.describeExecution(builder -> builder.executionArn(executionArn)).input())
                .isEqualTo("{\"quoteId\":\"" + quoteId + "\",\"version\":1}");
        Map<String, AttributeValue> start = item(QuoteKeys.outboxPk(created.startEventId()), "META");
        assertThat(start).containsKey("publishedAt").doesNotContainKey("gsi2pk");

        LambdaEvents.BatchResponse redelivered = relay.handleRequest(streamOf(
                item(QuoteKeys.outboxPk(created.startEventId()), "META"),
                item(QuoteKeys.outboxPk(created.acceptedEventId()), "META")), null);
        reconciler.handleRequest(Map.of("source", "test"), null);

        assertThat(redelivered.batchItemFailures()).isEmpty();
        assertThat(sfn.listExecutions(builder -> builder.stateMachineArn(flowMachineArn)).executions())
                .as("executions of the quote under any name")
                .filteredOn(execution -> execution.name().startsWith("q-" + quoteId + "-"))
                .extracting(execution -> execution.name())
                .containsExactly("q-" + quoteId + "-v1");
        List<JsonNode> accepted = capturedEvents(quoteId, QuoteEvents.QUOTE_ACCEPTED, 2);
        assertThat(accepted).allSatisfy(event -> {
            assertThat(event.get("source").asText()).isEqualTo("mmda.quote");
            assertThat(event.at("/detail/eventId").asText()).isEqualTo(created.acceptedEventId());
        });
    }

    @Test
    void relayPublishesRequestsToThePlatformBusAndMarksThemPublished() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 2021, false, "2012-03-15");
        step("load-snapshot", quoteId);
        step("evaluate-eligibility", quoteId);
        Task task = newTask();
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.ENRICHMENT, null);
        dispatch.handleRequest(new DispatchRequest(WorkRef.Kind.ENRICHMENT, quoteId, 1, null, task.executionArn(),
                task.token(), 20), null);

        Map<String, AttributeValue> outbox = item(QuoteKeys.outboxPk(workRef.requestEventId()), "META");
        assertThat(relay.handleRequest(streamOf(outbox), null).batchItemFailures()).isEmpty();

        JsonNode event = capturedEvents(quoteId, QuoteEvents.ENRICHMENT_REQUESTED, 1).getFirst();
        assertThat(event.at("/detail/eventId").asText()).isEqualTo(workRef.requestEventId());
        assertThat(event.at("/detail/params/vehicleRef").asText()).isEqualTo("vehicle-it");
        assertThat(event.toString()).doesNotContain(task.token());
        assertThat(item(QuoteKeys.outboxPk(workRef.requestEventId()), "META")).containsKey("publishedAt");
        sfn.stopExecution(builder -> builder.executionArn(task.executionArn()));
    }

    @Test
    void t01_twoInsurersQuoteInTheMapAndEveryOfferLinksItsEvidence() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, AttributeValue> selected = version(quoteId);
        assertThat(Attr.strings(selected, "selectedInsurers")).containsExactly("alpha", "beta");
        assertThat(selected.get("selectionRuleVersion").s()).isEqualTo("sel-1");
        assertThat(selected.get("phase").s()).isEqualTo("QUOTING");
        for (String insurerId : List.of("alpha", "beta")) {
            assertThat(result(quoteId, insurerId).get("status").s()).isEqualTo("PENDING");
        }
        assertThat(result(quoteId, "beta").get("insurerName").s()).isEqualTo("Beta Seguros");

        Map<String, Task> tasks = dispatchInsurers(quoteId, "alpha", "beta");
        String enrichmentEvidence = evidence(quoteId, WorkRef.of(quoteId, 1, WorkRef.Kind.ENRICHMENT, null))
                .get("evidenceId").s();
        for (String insurerId : List.of("alpha", "beta")) {
            assertThat(result(quoteId, insurerId).get("status").s()).isEqualTo("PROCESSING");
            JsonNode request = outboxDetail(insurerRef(quoteId, insurerId).requestEventId());
            assertThat(request.get("insurerId").asText()).isEqualTo(insurerId);
            assertThat(request.at("/enrichment/evidenceId").asText()).isEqualTo(enrichmentEvidence);
        }
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).hasSize(2);

        for (String insurerId : List.of("beta", "alpha")) {
            WorkRef workRef = insurerRef(quoteId, insurerId);
            LambdaEvents.BatchResponse response = resume.handleRequest(QuoteFixtures.sqs(QuoteFixtures.insurerResult(
                    workRef.value(), quoteId, insurerId, "QUOTED", QuoteFixtures.insurerResultEventId(workRef.value()))),
                    null);
            assertThat(response.batchItemFailures()).isEmpty();
            assertThat(succeededOutput(tasks.get(insurerId)).get("status").asText()).isEqualTo("QUOTED");
            assertThat(waiter(workRef).get("status").s()).isEqualTo("DONE");
        }

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("COMPLETED");
        assertThat(consolidation.get("outcomeReason")).isNull();
        assertThat(offers(quoteId)).extracting(offer -> offer.get("insurerId").s())
                .containsExactlyInAnyOrder("alpha", "beta");
        for (Map<String, AttributeValue> offer : offers(quoteId)) {
            String insurerId = offer.get("insurerId").s();
            WorkRef workRef = insurerRef(quoteId, insurerId);
            Map<String, AttributeValue> result = result(quoteId, insurerId);
            assertThat(offer.get("offerId").s()).isEqualTo(QuoteKeys.offerId(workRef));
            assertThat(offer.get("insurerName").s()).isEqualTo(result.get("insurerName").s());
            assertThat(offer.get("queryId").s()).isEqualTo(QuoteFixtures.providerQueryId(workRef.value()))
                    .isEqualTo(result.get("queryRef").s());
            assertThat(result.get("attemptRef").s()).isEqualTo(offer.get("queryId").s() + "#0001");
            assertThat(offer.get("rawResponseRef").s()).startsWith("raw/" + offer.get("queryId").s());
        }
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("offerCount").n()).isEqualTo("2");
        assertThat(outboxDetail(QuoteEvents.completedEventId(quoteId, 1)).get("offerCount").asInt()).isEqualTo(2);
    }

    @Test
    void t07_offerAndDeclineCompleteWithOneOfferAndTheDeclineReason() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, Task> tasks = dispatchInsurers(quoteId, "alpha", "beta");

        resumeInsurer(quoteId, "alpha", "QUOTED");
        resumeInsurer(quoteId, "beta", "DECLINED");
        assertThat(succeededOutput(tasks.get("beta")).get("status").asText()).isEqualTo("DECLINED");

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("COMPLETED");
        assertThat(consolidation.get("outcomeReason")).isNull();
        assertThat(offers(quoteId)).singleElement()
                .satisfies(offer -> assertThat(offer.get("insurerId").s()).isEqualTo("alpha"));
        Map<String, AttributeValue> declined = result(quoteId, "beta");
        assertThat(declined.get("status").s()).isEqualTo("DECLINED");
        assertThat(declined.get("reasonCode").s()).isEqualTo("RISK_NOT_ACCEPTED");
    }

    @Test
    void t08_offerIsVisibleBeforeTheOtherInsurerTimesOutAndTheVersionEndsPartial() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, Task> tasks = dispatchInsurers(quoteId, "alpha", "beta");

        resumeInsurer(quoteId, "alpha", "QUOTED");
        assertThat(succeededOutput(tasks.get("alpha")).get("status").asText()).isEqualTo("QUOTED");
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("QUOTED");
        assertThat(result(quoteId, "beta").get("status").s()).as("beta still waits").isEqualTo("PROCESSING");
        assertThat(offers(quoteId)).as("the offer is recorded before the consolidation").hasSize(1);
        assertThat(version(quoteId).get("phase").s()).isEqualTo("QUOTING");
        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("phase").s()).isEqualTo("QUOTING");
        assertThat(meta.get("offerCount").n()).isEqualTo("1");

        Map<String, Object> recorded = steps.handleRequest(new StepRequest("record-insurer-outcome", quoteId, 1, "beta",
                "TIMED_OUT", objectMapper.readTree("{\"Error\":\"States.Timeout\",\"Cause\":\"\"}")), null);
        assertThat(recorded.get("status")).isEqualTo("TIMED_OUT");
        assertThat(result(quoteId, "beta").get("reasonCode").s()).isEqualTo("STEP_TIMEOUT");
        assertThat(waiter(insurerRef(quoteId, "beta")).get("status").s()).isEqualTo("EXPIRED");

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("PARTIAL");
        assertThat(consolidation.get("outcomeReason")).isNull();
        String revision = version(quoteId).get("revision").n();

        WorkRef beta = insurerRef(quoteId, "beta");
        assertThat(resumer.handle(QuoteFixtures.insurerResult(beta.value(), quoteId, "beta", "QUOTED",
                QuoteFixtures.insurerResultEventId(beta.value())))).isEqualTo(ResultResumer.Disposition.LATE);
        assertThat(lateItems(quoteId)).singleElement().satisfies(late -> {
            assertThat(late.get("sk").s()).isEqualTo(QuoteKeys.lateSk(1, "beta", QuoteFixtures.providerQueryId(beta.value())));
            assertThat(late.get("reason").s()).isEqualTo("LATE");
            assertThat(late.get("status").s()).isEqualTo("QUOTED");
            assertThat(late).containsKeys("offerSummary", "rawResponseRef");
        });
        assertThat(result(quoteId, "beta").get("status").s()).isEqualTo("TIMED_OUT");
        assertThat(offers(quoteId)).as("a late offer is never selectable (RN10)").hasSize(1);
        assertThat(version(quoteId).get("outcome").s()).isEqualTo("PARTIAL");
        assertThat(version(quoteId).get("revision").n()).isEqualTo(revision);
        assertThat(step("consolidate", quoteId).get("outcome")).isEqualTo("PARTIAL");
        sfn.stopExecution(builder -> builder.executionArn(tasks.get("beta").executionArn()));
    }

    @Test
    void betaNeedsInformationFollowsThePrecedenceWithAndWithoutAnOffer() throws Exception {
        String withOffer = quoteReadyToQuote(Usage.PERSONAL);
        dispatchInsurers(withOffer, "alpha", "beta");
        resumeInsurer(withOffer, "alpha", "QUOTED");
        resumeInsurer(withOffer, "beta", "NEEDS_INFORMATION");

        assertThat(step("consolidate", withOffer).get("outcome")).isEqualTo("PARTIAL");
        JsonNode betaMissing = objectMapper.readTree(result(withOffer, "beta").get("missingFields").s());
        assertThat(betaMissing.get(0).get("path").asText()).isEqualTo("risk.principalDriver.licenseDate");

        String withoutOffer = quoteReadyToQuote(Usage.PERSONAL);
        dispatchInsurers(withoutOffer, "alpha", "beta");
        resumeInsurer(withoutOffer, "alpha", "DECLINED");
        resumeInsurer(withoutOffer, "beta", "NEEDS_INFORMATION");

        Map<String, Object> consolidation = step("consolidate", withoutOffer);
        assertThat(consolidation.get("outcome")).isEqualTo("NEEDS_INFORMATION");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("INSURER_NEEDS_INFORMATION");
        JsonNode missing = objectMapper.readTree(version(withoutOffer).get("missingFields").s());
        assertThat(missing).singleElement().satisfies(field -> {
            assertThat(field.get("path").asText()).isEqualTo("risk.principalDriver.licenseDate");
            assertThat(field.get("reasonCode").asText()).isEqualTo("MISSING_LICENSE_DATE");
        });
    }

    @Test
    void t11_duplicatedBetaCallbackKeepsOneOfferAndTheResumeIsIdempotent() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, Task> tasks = dispatchInsurers(quoteId, "alpha", "beta");
        WorkRef beta = insurerRef(quoteId, "beta");
        String eventId = QuoteFixtures.insurerResultEventId(beta.value());
        String quoted = QuoteFixtures.insurerResult(beta.value(), quoteId, "beta", "QUOTED", eventId);

        assertThat(resumer.handle(quoted)).isEqualTo(ResultResumer.Disposition.RESUMED);
        assertThat(succeededOutput(tasks.get("beta")).get("status").asText()).isEqualTo("QUOTED");
        LambdaEvents.BatchResponse republished = resume.handleRequest(QuoteFixtures.sqs(quoted), null);
        assertThat(republished.batchItemFailures()).isEmpty();
        assertThat(resumer.handle(quoted)).as("same eventId of the same queryId").isEqualTo(ResultResumer.Disposition.DISCARDED);
        assertThat(resumer.handle(QuoteFixtures.insurerResult(beta.value(), quoteId, "beta", "QUOTED",
                UUID.randomUUID().toString()))).isEqualTo(ResultResumer.Disposition.DUPLICATE);

        Task retry = newTask();
        assertThat(dispatch.handleRequest(insurerRequest(quoteId, "beta", retry), null).get("status")).isEqualTo("RESUMED");
        assertThat(succeededOutput(retry).get("offerId").asText()).isEqualTo(QuoteKeys.offerId(beta));
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).as("no new request").hasSize(2);

        resumeInsurer(quoteId, "alpha", "QUOTED");
        assertThat(step("consolidate", quoteId).get("outcome")).isEqualTo("COMPLETED");
        assertThat(offers(quoteId)).filteredOn(offer -> offer.get("insurerId").s().equals("beta")).hasSize(1);
        assertThat(offers(quoteId)).hasSize(2);
        assertThat(result(quoteId, "beta").get("revision").n()).as("PENDING, PROCESSING and QUOTED").isEqualTo("3");
        assertThat(lateItems(quoteId)).singleElement()
                .satisfies(late -> assertThat(late.get("reason").s()).isEqualTo("DUPLICATE"));
        assertThat(item(QuoteKeys.consumedPk(eventId), "META")).isNotNull();
    }

    @Test
    void t26_enrichedVehicleBefore2000EndsAsNoOfferWithoutQueryingInsurers() throws Exception {
        String quoteId = eligibleQuote(Usage.PERSONAL);
        resolveEnrichment(quoteId, 1998);

        assertThat(step("select-insurers", quoteId).get("insurers")).isEqualTo(List.of());
        assertThat(step("select-insurers", quoteId).get("insurers")).as("a retry reads the empty selection")
                .isEqualTo(List.of());
        Map<String, AttributeValue> version = version(quoteId);
        assertThat(Attr.strings(version, "selectedInsurers")).isEmpty();
        assertThat(version.get("selectionRuleVersion").s()).isEqualTo("sel-1");
        assertThat(version.get("phase").s()).as("no insurer, no QUOTING").isEqualTo("ENRICHING");

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("NO_OFFER");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("NO_APPLICABLE_INSURER");
        assertThat(query(quoteId, QuoteKeys.resultPrefix(1))).isEmpty();
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).isEmpty();
        assertThat(outboxDetail(QuoteEvents.completedEventId(quoteId, 1)).get("outcomeReason").asText())
                .isEqualTo("NO_APPLICABLE_INSURER");
        assertThat(outboxDetail(QuoteEvents.completedEventId(quoteId, 1)).get("offerCount").asInt()).isZero();
    }

    @Test
    void selectionUsesTheEnrichedVehicleYearInsteadOfTheInputYear() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 1998, false, "2012-03-15");
        step("load-snapshot", quoteId);
        assertThat(step("evaluate-eligibility", quoteId).get("decision")).isEqualTo("ELIGIBLE");
        resolveEnrichment(quoteId, 2021);

        assertThat(step("select-insurers", quoteId).get("insurers")).isEqualTo(List.of("alpha", "beta"));
        assertThat(step("select-insurers", quoteId).get("insurers")).as("a retry reads what was recorded")
                .isEqualTo(List.of("alpha", "beta"));
        assertThat(query(quoteId, QuoteKeys.resultPrefix(1))).hasSize(2);
    }

    private String newQuote(Usage usage, int year, boolean salvage, String licenseDate) throws Exception {
        String quoteId = UUID.randomUUID().toString();
        QuoteFixtures.createQuote(dynamoDb, quoteId, QuoteFixtures.snapshot(usage, year, salvage, licenseDate),
                Instant.now());
        return quoteId;
    }

    /** Cotação elegível, na fase {@code ENRICHING}, pronta para o pedido de enriquecimento. */
    private String eligibleQuote(Usage usage) throws Exception {
        String quoteId = newQuote(usage, 2021, false, "2012-03-15");
        step("load-snapshot", quoteId);
        assertThat(step("evaluate-eligibility", quoteId).get("decision")).isEqualTo("ELIGIBLE");
        return quoteId;
    }

    /** Leva a cotação até o {@code select-insurers}, com o enriquecimento resolvido por um token real. */
    private String quoteReadyToQuote(Usage usage) throws Exception {
        String quoteId = newQuote(usage, 2021, false, "2012-03-15");
        step("load-snapshot", quoteId);
        step("evaluate-eligibility", quoteId);
        resolveEnrichment(quoteId, 2021);
        step("select-insurers", quoteId);
        return quoteId;
    }

    private void resolveEnrichment(String quoteId, int vehicleYear) throws Exception {
        Task task = newTask();
        WorkRef workRef = WorkRef.of(quoteId, 1, WorkRef.Kind.ENRICHMENT, null);
        dispatch.handleRequest(new DispatchRequest(WorkRef.Kind.ENRICHMENT, quoteId, 1, null, task.executionArn(),
                task.token(), 20), null);
        assertThat(outboxOf(quoteId, QuoteEvents.ENRICHMENT_REQUESTED)).hasSize(1);
        assertThat(resumer.handle(QuoteFixtures.enrichmentResult(workRef.value(), quoteId, "FOUND", vehicleYear,
                UUID.randomUUID().toString()))).isEqualTo(ResultResumer.Disposition.RESUMED);
        assertThat(succeededOutput(task).get("resultType").asText()).isEqualTo("FOUND");
    }

    private Map<String, Object> step(String name, String quoteId) {
        return steps.handleRequest(new StepRequest(name, quoteId, 1, null, null, null), null);
    }

    private DispatchRequest insurerRequest(String quoteId, Task task) {
        return insurerRequest(quoteId, "alpha", task);
    }

    private DispatchRequest insurerRequest(String quoteId, String insurerId, Task task) {
        return new DispatchRequest(WorkRef.Kind.INSURER, quoteId, 1, insurerId, task.executionArn(), task.token(), 20);
    }

    private static WorkRef insurerRef(String quoteId, String insurerId) {
        return WorkRef.of(quoteId, 1, WorkRef.Kind.INSURER, insurerId);
    }

    /** Um item do Map por seguradora, cada um com o próprio task token, como a máquina os despacha. */
    private Map<String, Task> dispatchInsurers(String quoteId, String... insurerIds) {
        Map<String, Task> tasks = new HashMap<>();
        for (String insurerId : insurerIds) {
            Task task = newTask();
            assertThat(dispatch.handleRequest(insurerRequest(quoteId, insurerId, task), null).get("status"))
                    .isEqualTo("WAITING");
            tasks.put(insurerId, task);
        }
        return tasks;
    }

    /** Resultado da seguradora pela fila {@code results}, com o {@code eventId} determinístico da provider. */
    private void resumeInsurer(String quoteId, String insurerId, String status) {
        WorkRef workRef = insurerRef(quoteId, insurerId);
        LambdaEvents.BatchResponse response = resume.handleRequest(QuoteFixtures.sqs(QuoteFixtures.insurerResult(
                workRef.value(), quoteId, insurerId, status, QuoteFixtures.insurerResultEventId(workRef.value()))), null);
        assertThat(response.batchItemFailures()).isEmpty();
        assertThat(result(quoteId, insurerId).get("status").s()).isEqualTo(status);
    }

    private DispatchRequest enrichmentRequest(String quoteId, Task task) {
        return new DispatchRequest(WorkRef.Kind.ENRICHMENT, quoteId, 1, null, task.executionArn(), task.token(), 20);
    }

    private record Task(String token, String executionArn) {
    }

    /** Inicia a {@code token-machine} e lê o task token real que ela envia à fila. */
    private Task newTask() {
        String executionArn = sfn.startExecution(builder -> builder.stateMachineArn(tokenMachineArn).input("{}"))
                .executionArn();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            for (Message message : sqs.receiveMessage(builder -> builder.queueUrl(tokenQueueUrl)
                    .maxNumberOfMessages(10).waitTimeSeconds(1)).messages()) {
                JsonNode body = objectMapper.readTree(message.body());
                tokensByExecution.put(body.get("executionArn").asText(), body.get("taskToken").asText());
                sqs.deleteMessage(builder -> builder.queueUrl(tokenQueueUrl).receiptHandle(message.receiptHandle()));
            }
            return tokensByExecution.containsKey(executionArn);
        });
        return new Task(tokensByExecution.get(executionArn), executionArn);
    }

    private DescribeExecutionResponse execution(Task task) {
        return sfn.describeExecution(builder -> builder.executionArn(task.executionArn()));
    }

    private JsonNode succeededOutput(Task task) throws Exception {
        await().atMost(Duration.ofSeconds(15)).until(() -> execution(task).status() == ExecutionStatus.SUCCEEDED);
        return objectMapper.readTree(execution(task).output());
    }

    private List<JsonNode> capturedEvents(String quoteId, String detailType, int expected) {
        List<JsonNode> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            for (Message message : sqs.receiveMessage(builder -> builder.queueUrl(captureQueueUrl)
                    .maxNumberOfMessages(10).waitTimeSeconds(1)).messages()) {
                JsonNode event = objectMapper.readTree(message.body());
                if (quoteId.equals(event.at("/detail/quoteId").asText())
                        && detailType.equals(event.get("detail-type").asText())) {
                    found.add(event);
                }
                sqs.deleteMessage(builder -> builder.queueUrl(captureQueueUrl).receiptHandle(message.receiptHandle()));
            }
            return found.size() >= expected;
        });
        return found;
    }

    private void assertNoExternalQuery(String quoteId) {
        assertThat(outboxOf(quoteId, QuoteEvents.ENRICHMENT_REQUESTED)).isEmpty();
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).isEmpty();
        assertThat(dynamoDb.scan(builder -> builder.tableName(FlociResource.TABLE)
                .filterExpression("#type = :waiter AND quoteId = :quoteId")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":waiter", AttributeValue.fromS("WAITER"),
                        ":quoteId", AttributeValue.fromS(quoteId)))).items()).isEmpty();
        assertThat(outboxOf(quoteId, QuoteEvents.QUOTE_COMPLETED)).hasSize(1);
    }

    /** Nenhuma seguradora selecionada, pedida ou aguardada (PRD §8, T25 e T27). */
    private void assertNoInsurerQueried(String quoteId) {
        assertThat(version(quoteId)).doesNotContainKeys("selectedInsurers", "selectionRuleVersion");
        assertThat(query(quoteId, QuoteKeys.resultPrefix(1))).isEmpty();
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).isEmpty();
        assertThat(dynamoDb.scan(builder -> builder.tableName(FlociResource.TABLE)
                .filterExpression("#type = :waiter AND quoteId = :quoteId AND kind = :insurer")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":waiter", AttributeValue.fromS("WAITER"),
                        ":quoteId", AttributeValue.fromS(quoteId), ":insurer", AttributeValue.fromS("INSURER"))))
                .items()).isEmpty();
        assertThat(version(quoteId).get("phase").s()).isEqualTo("TERMINAL");
        assertThat(outboxOf(quoteId, QuoteEvents.QUOTE_COMPLETED)).hasSize(1);
    }

    private Map<String, AttributeValue> evidence(String quoteId, WorkRef workRef) {
        return item(QuoteKeys.quotePk(quoteId), QuoteKeys.evidenceSk(1, workRef));
    }

    private LambdaEvents.StreamEvent streamOf(Map<String, AttributeValue>... items) {
        List<LambdaEvents.StreamRecord> records = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            Map<String, JsonNode> image = new HashMap<>();
            items[i].forEach((name, value) -> image.put(name, value.n() != null
                    ? objectMapper.createObjectNode().put("N", value.n())
                    : objectMapper.createObjectNode().put("S", value.s())));
            records.add(new LambdaEvents.StreamRecord("event-" + i, "INSERT",
                    new LambdaEvents.StreamData(image, String.valueOf(1000 + i))));
        }
        return new LambdaEvents.StreamEvent(records);
    }

    private JsonNode outboxDetail(String eventId) throws Exception {
        return objectMapper.readTree(item(QuoteKeys.outboxPk(eventId), "META").get("detail").s());
    }

    private List<Map<String, AttributeValue>> outboxOf(String quoteId, String detailType) {
        return dynamoDb.scan(builder -> builder.tableName(FlociResource.TABLE)
                .filterExpression("#type = :outbox AND quoteId = :quoteId AND detailType = :detailType")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":outbox", AttributeValue.fromS("OUTBOX"),
                        ":quoteId", AttributeValue.fromS(quoteId), ":detailType", AttributeValue.fromS(detailType))))
                .items();
    }

    private List<Map<String, AttributeValue>> offers(String quoteId) {
        return query(quoteId, QuoteKeys.offerPrefix(1));
    }

    private List<Map<String, AttributeValue>> lateItems(String quoteId) {
        return query(quoteId, QuoteKeys.versionSk(1) + "#LATE#");
    }

    private List<Map<String, AttributeValue>> query(String quoteId, String prefix) {
        return dynamoDb.query(builder -> builder.tableName(FlociResource.TABLE).consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)),
                        ":prefix", AttributeValue.fromS(prefix)))).items();
    }

    private Map<String, AttributeValue> version(String quoteId) {
        return item(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(1));
    }

    private Map<String, AttributeValue> result(String quoteId, String insurerId) {
        return item(QuoteKeys.quotePk(quoteId), QuoteKeys.resultSk(1, insurerId));
    }

    private Map<String, AttributeValue> waiter(WorkRef workRef) {
        return item(QuoteKeys.waiterPk(workRef), "META");
    }

    private Map<String, AttributeValue> item(String pk, String sk) {
        Map<String, AttributeValue> item = dynamoDb.getItem(builder -> builder.tableName(FlociResource.TABLE)
                .consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk)))).item();
        return item == null || item.isEmpty() ? null : item;
    }
}

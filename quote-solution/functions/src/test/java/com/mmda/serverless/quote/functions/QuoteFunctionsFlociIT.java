package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Usage;
import com.mmda.serverless.quote.domain.WorkRef;
import com.mmda.serverless.quote.functions.DispatchHandler.DispatchRequest;
import com.mmda.serverless.quote.functions.QuoteFixtures.EvidenceRef;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Passos, {@code dispatch}, {@code resume}, relay e reconciler contra DynamoDB,
 * EventBridge, SQS, Secrets
 * Manager e Step Functions reais do Floci, com task tokens reais. A máquina da
 * quote não é implantada: os
 * passos são chamados na ordem em que ela os chama, e o deploy de verificação
 * cobre a máquina.
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

    // WHY: os padrões só existem para a classe validar na inicialização dos testes
    // do surefire, sem o Floci
    @ConfigProperty(name = "it.capture-queue-url", defaultValue = "unset")
    String captureQueueUrl;

    @ConfigProperty(name = "it.token-queue-url", defaultValue = "unset")
    String tokenQueueUrl;

    @ConfigProperty(name = "it.token-machine-arn", defaultValue = "unset")
    String tokenMachineArn;

    @ConfigProperty(name = "quote.state-machine-arn")
    String flowMachineArn;

    @ConfigProperty(name = "it.fail-machine-arn", defaultValue = "unset")
    String failMachineArn;

    @ConfigProperty(name = "it.timeout-machine-arn", defaultValue = "unset")
    String timeoutMachineArn;

    @ConfigProperty(name = "reconciler.results-queue-url")
    String resultsQueueUrl;

    @ConfigProperty(name = "reconciler.results-dead-letter-queue-url")
    String deadLetterQueueUrl;

    @Inject
    WaiterReconciler waiterReconciler;

    @Inject
    VersionReconciler versionReconciler;

    @Inject
    DeadLetterReprocessor deadLetters;

    @Inject
    IssueProjectorHandler issueProjector;

    @Inject
    IssueProjector issueProjectorLogic;

    @Inject
    IssueReviewer issueReviewer;

    @ConfigProperty(name = "reconciler.issues-queue-url")
    String issuesQueueUrl;

    @ConfigProperty(name = "reconciler.issues-dead-letter-queue-url")
    String issuesDeadLetterQueueUrl;

    @Inject
    QuoteTable table;

    @Inject
    TokenCipher cipher;

    @Inject
    QuoteFunctionsConfig config;

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
                QuoteFixtures.sqs(
                        QuoteFixtures.insurerResult(workRef.value(), quoteId, "QUOTED", UUID.randomUUID().toString())),
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

        Map<String, Object> recorded = steps
                .handleRequest(new StepRequest("record-insurer-outcome", quoteId, 1, "alpha",
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

        assertThat(step("select-insurers", quoteId).get("insurers")).as("no insurer even if the step runs")
                .isEqualTo(List.of());
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

        List<String> logged = new CopyOnWriteArrayList<>();
        Map<String, Object> report = logging(OutboxReconciler.class, logged,
                () -> reconciler.handleRequest(Map.of("source", "test"), null));

        assertThat((Integer) report.get("republished")).isGreaterThanOrEqualTo(2);
        assertThat(logged).as("each republished outbox is identified in the log")
                .anySatisfy(line -> assertThat(line).contains("eventId=" + created.startEventId(),
                        "detailType=" + QuoteEvents.START_QUOTE_REQUESTED))
                .anySatisfy(line -> assertThat(line).contains("eventId=" + created.acceptedEventId(),
                        "detailType=" + QuoteEvents.QUOTE_ACCEPTED));
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
                    workRef.value(), quoteId, insurerId, "QUOTED",
                    QuoteFixtures.insurerResultEventId(workRef.value()))),
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
            assertThat(late.get("sk").s())
                    .isEqualTo(QuoteKeys.lateSk(1, "beta", QuoteFixtures.providerQueryId(beta.value())));
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
        assertThat(resumer.handle(quoted)).as("same eventId of the same queryId")
                .isEqualTo(ResultResumer.Disposition.DISCARDED);
        assertThat(resumer.handle(QuoteFixtures.insurerResult(beta.value(), quoteId, "beta", "QUOTED",
                UUID.randomUUID().toString()))).isEqualTo(ResultResumer.Disposition.DUPLICATE);

        Task retry = newTask();
        assertThat(dispatch.handleRequest(insurerRequest(quoteId, "beta", retry), null).get("status"))
                .isEqualTo("RESUMED");
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

    @Test
    void t09_allInsurersDeclineEndsAsNoOfferWithoutCommercialRetry() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, Task> tasks = dispatchInsurers(quoteId, "alpha", "beta");

        resumeInsurer(quoteId, "alpha", "DECLINED");
        resumeInsurer(quoteId, "beta", "DECLINED");
        for (Task task : tasks.values()) {
            assertThat(succeededOutput(task).get("status").asText()).isEqualTo("DECLINED");
        }
        Task retry = newTask();
        assertThat(dispatch.handleRequest(insurerRequest(quoteId, "alpha", retry), null).get("status"))
                .as("a task retry receives the recorded decline").isEqualTo("RESUMED");

        Map<String, Object> consolidation = step("consolidate", quoteId);
        assertThat(consolidation.get("outcome")).isEqualTo("NO_OFFER");
        assertThat(consolidation.get("outcomeReason")).isEqualTo("ALL_INSURERS_DECLINED");
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).as("one request per insurer").hasSize(2);
        assertThat(offers(quoteId)).isEmpty();
        assertThat(result(quoteId, "beta").get("reasonCode").s()).isEqualTo("RISK_NOT_ACCEPTED");
    }

    @Test
    void t10_allInsurersUnavailableEndAsTechnicalFailureNotNoOffer() throws Exception {
        String reported = quoteReadyToQuote(Usage.PERSONAL);
        dispatchInsurers(reported, "alpha", "beta");
        resumeInsurer(reported, "alpha", "UNAVAILABLE");
        resumeInsurer(reported, "beta", "UNAVAILABLE");

        String timedOut = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, Task> tasks = dispatchInsurers(timedOut, "alpha", "beta");
        resumeInsurer(timedOut, "alpha", "UNAVAILABLE");
        steps.handleRequest(new StepRequest("record-insurer-outcome", timedOut, 1, "beta", "TIMED_OUT",
                objectMapper.readTree("{\"Error\":\"States.Timeout\",\"Cause\":\"\"}")), null);

        for (String quoteId : List.of(reported, timedOut)) {
            Map<String, Object> consolidation = step("consolidate", quoteId);
            assertThat(consolidation.get("outcome")).isEqualTo("TECHNICAL_FAILURE");
            assertThat(consolidation.get("outcomeReason")).isEqualTo("INSURER_TECHNICAL_FAILURE");
            assertThat(offers(quoteId)).isEmpty();
        }
        assertThat(result(timedOut, "beta").get("status").s()).isEqualTo("TIMED_OUT");
        sfn.stopExecution(builder -> builder.executionArn(tasks.get("beta").executionArn()));
    }

    @Test
    void t16_resultAfterTheFinalDeadlineIsKeptAsEvidenceAndTheTerminalQuoteDoesNotReopen() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        steps.handleRequest(new StepRequest("record-insurer-outcome", quoteId, 1, "alpha", "TIMED_OUT",
                objectMapper.readTree("{\"Error\":\"States.Timeout\",\"Cause\":\"\"}")), null);
        assertThat(step("consolidate", quoteId).get("outcome")).isEqualTo("TECHNICAL_FAILURE");
        Map<String, AttributeValue> terminal = version(quoteId);
        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");

        assertThat(resumer.handle(QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha", "QUOTED",
                QuoteFixtures.insurerResultEventId(workRef.value())))).isEqualTo(ResultResumer.Disposition.LATE);

        assertThat(version(quoteId)).isEqualTo(terminal);
        assertThat(item(QuoteKeys.quotePk(quoteId), "META")).isEqualTo(meta);
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("TIMED_OUT");
        assertThat(offers(quoteId)).isEmpty();
        assertThat(lateItems(quoteId)).singleElement().satisfies(late -> {
            assertThat(late.get("reason").s()).isEqualTo("LATE");
            assertThat(late.get("offerSummary").s()).contains("2450.90");
        });
        sfn.stopExecution(builder -> builder.executionArn(task.executionArn()));
    }

    @Test
    void t17_resultOfASupersededVersionStaysInItAndDoesNotTouchTheCurrentSummary() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        QuoteFixtures.supersedeFirstVersion(dynamoDb, quoteId, QuoteFixtures.snapshot(Usage.PERSONAL, 2019, false,
                "2012-03-15"), Instant.now());

        resumeInsurer(quoteId, "alpha", "QUOTED");
        assertThat(succeededOutput(task).get("status").asText()).isEqualTo("QUOTED");
        assertThat(step("consolidate", quoteId).get("outcome")).isEqualTo("COMPLETED");

        assertThat(version(quoteId)).containsKey("supersededAt");
        assertThat(version(quoteId).get("phase").s()).isEqualTo("TERMINAL");
        assertThat(offers(quoteId)).hasSize(1);
        assertThat(query(quoteId, QuoteKeys.versionSk(2))).as("the new version has no result of the old one")
                .extracting(item -> item.get("sk").s()).containsExactly("V#0002");
        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("currentVersion").n()).isEqualTo("2");
        assertThat(meta.get("phase").s()).isEqualTo("RECEIVED");
        assertThat(meta.get("offerCount").n()).isEqualTo("0");
        assertThat(meta).doesNotContainKey("outcome");
    }

    @Test
    void t13_storedResultWhoseResumeFailedIsResumedByTheReconcilerWithoutANewRequest() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        String body = QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha", "QUOTED",
                QuoteFixtures.insurerResultEventId(workRef.value()));
        ResultResumer crashing = new ResultResumer(table, cipher, unreachableSfn(), objectMapper, Clock.systemUTC());

        assertThatThrownBy(() -> crashing.handle(body)).isInstanceOf(SdkClientException.class);
        assertThat(waiter(workRef).get("status").s()).isEqualTo("RESUMING");
        assertThat(waiter(workRef).get("gsi2pk").s()).isEqualTo("WAITER_RESUMING");
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("QUOTED");
        assertThat(execution(task).status()).isEqualTo(ExecutionStatus.RUNNING);

        waiterReconciler.resumeStale(Instant.now());
        assertThat(waiter(workRef).get("status").s()).as("younger than resumingRetryAfterSeconds")
                .isEqualTo("RESUMING");
        waiterReconciler.resumeStale(Instant.now().plusSeconds(60));

        assertThat(succeededOutput(task).get("status").asText()).isEqualTo("QUOTED");
        assertThat(waiter(workRef).get("status").s()).isEqualTo("DONE");
        assertThat(waiter(workRef)).doesNotContainKey("gsi2pk");
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).as("no new request to the insurer")
                .hasSize(1);
        assertThat(offers(quoteId)).hasSize(1);
        assertThat(resumer.handle(body)).as("the redelivered message").isEqualTo(ResultResumer.Disposition.DISCARDED);
        assertThat(offers(quoteId)).hasSize(1);
    }

    @Test
    void t20_failedExecutionIsFinalizedWithTheRecordedOfferAndAWorkflowFailedIssue() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.PERSONAL);
        Map<String, Task> tasks = dispatchInsurers(quoteId, "alpha", "beta");
        resumeInsurer(quoteId, "alpha", "QUOTED");
        attachExecution(quoteId, failMachineArn, ExecutionStatus.FAILED);

        versionReconciler.reconcile(Instant.now());

        Map<String, AttributeValue> version = version(quoteId);
        assertThat(version.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(version.get("outcome").s()).isEqualTo("PARTIAL");
        assertThat(version).doesNotContainKeys("outcomeReason", "gsi2pk");
        assertThat(result(quoteId, "alpha").get("status").s()).isEqualTo("QUOTED");
        assertThat(result(quoteId, "beta").get("status").s()).isEqualTo("TIMED_OUT");
        assertThat(result(quoteId, "beta").get("reasonCode").s()).isEqualTo("WORKFLOW_FAILED");
        assertThat(offers(quoteId)).as("the recorded offer stays").hasSize(1);
        JsonNode completed = outboxDetail(QuoteEvents.completedEventId(quoteId, 1));
        assertThat(completed.get("outcome").asText()).isEqualTo("PARTIAL");
        assertThat(completed.get("offerCount").asInt()).isEqualTo(1);
        Map<String, AttributeValue> issue = issue(QuoteIssues.Type.WORKFLOW_FAILED,
                QuoteIssues.versionTarget(quoteId, 1));
        assertThat(issue.get("type").s()).as("the item type (ADR 0015)").isEqualTo("ISSUE");
        assertThat(issue.get("issueType").s()).isEqualTo("WORKFLOW_FAILED");
        assertThat(issue.get("status").s()).isEqualTo("OPEN");
        assertThat(issue.get("source").s()).isEqualTo("quote");
        assertThat(issue.get("quoteId").s()).isEqualTo(quoteId);
        assertThat(issue.get("gsi1pk").s()).isEqualTo("ISSUES#org-acme");
        assertThat(issue.get("safeSummary").s()).contains("FAILED");
        assertThat(issue.get("revision").n()).isEqualTo("1");
        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(meta.get("outcome").s()).isEqualTo("PARTIAL");
        assertThat(meta.get("offerCount").n()).isEqualTo("1");
        WorkRef beta = insurerRef(quoteId, "beta");
        assertThat(waiter(beta).get("status").s()).isEqualTo("EXPIRED");
        assertThat(waiter(beta)).as("covered by WORKFLOW_FAILED, not by TASK_EXPIRED").doesNotContainKey("gsi2pk");

        versionReconciler.reconcile(Instant.now());
        assertThat(version(quoteId).get("revision").n()).isEqualTo(version.get("revision").n());
        assertThat(issue(QuoteIssues.Type.WORKFLOW_FAILED, QuoteIssues.versionTarget(quoteId, 1)).get("revision").n())
                .isEqualTo("1");
        assertThat(resumer.handle(QuoteFixtures.insurerResult(beta.value(), quoteId, "beta", "QUOTED",
                QuoteFixtures.insurerResultEventId(beta.value())))).isEqualTo(ResultResumer.Disposition.LATE);
        assertThat(offers(quoteId)).hasSize(1);
        assertThat(version(quoteId).get("outcome").s()).isEqualTo("PARTIAL");
        sfn.stopExecution(builder -> builder.executionArn(tasks.get("beta").executionArn()));
    }

    @Test
    void t20_timedOutOrAbortedExecutionIsFinalizedFromWhatTheVersionRecorded() throws Exception {
        String waiting = eligibleQuote(Usage.COMMERCIAL);
        Task enrichment = newTask();
        dispatch.handleRequest(enrichmentRequest(waiting, enrichment), null);
        attachExecution(waiting, timeoutMachineArn, ExecutionStatus.TIMED_OUT);
        String early = newQuote(Usage.COMMERCIAL, 2021, false, "2012-03-15");
        step("load-snapshot", early);
        attachExecution(early, flowMachineArn, ExecutionStatus.ABORTED);

        versionReconciler.reconcile(Instant.now());

        assertThat(version(waiting).get("outcome").s()).isEqualTo("TECHNICAL_FAILURE");
        assertThat(version(waiting).get("outcomeReason").s()).isEqualTo("ENRICHMENT_FAILED");
        assertThat(
                issue(QuoteIssues.Type.WORKFLOW_FAILED, QuoteIssues.versionTarget(waiting, 1)).get("safeSummary").s())
                .contains("TIMED_OUT");
        assertThat(version(early).get("outcome").s()).isEqualTo("TECHNICAL_FAILURE");
        assertThat(version(early).get("outcomeReason").s()).isEqualTo("WORKFLOW_FAILED");
        assertThat(issue(QuoteIssues.Type.WORKFLOW_FAILED, QuoteIssues.versionTarget(early, 1)).get("safeSummary").s())
                .contains("ABORTED");
        WorkRef workRef = WorkRef.of(waiting, 1, WorkRef.Kind.ENRICHMENT, null);
        assertThat(waiter(workRef).get("status").s()).isEqualTo("EXPIRED");

        assertThat(resumer.handle(QuoteFixtures.enrichmentResult(workRef.value(), waiting, "FOUND", 2021,
                UUID.randomUUID().toString()))).isEqualTo(ResultResumer.Disposition.LATE);
        assertThat(evidence(waiting, workRef)).as("a finalized version never gets a used evidence").isNull();
        sfn.stopExecution(builder -> builder.executionArn(enrichment.executionArn()));
    }

    @Test
    void versionPastTheDeadlineOpensStuckVersionOnceAndTheLostSummaryIsRepaired() throws Exception {
        String quoteId = UUID.randomUUID().toString();
        QuoteFixtures.createQuote(dynamoDb, quoteId, QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false, "2012-03-15"),
                Instant.now().minusSeconds(600));
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)), "sk",
                        AttributeValue.fromS("V#0001")))
                .updateExpression("SET phase = :quoting")
                .expressionAttributeValues(Map.of(":quoting", AttributeValue.fromS("QUOTING"))));

        versionReconciler.reconcile(Instant.now());

        Map<String, AttributeValue> stuck = issue(QuoteIssues.Type.STUCK_VERSION,
                QuoteIssues.versionTarget(quoteId, 1));
        assertThat(stuck.get("status").s()).isEqualTo("OPEN");
        assertThat(stuck.get("safeSummary").s()).contains("MISSING");
        assertThat(stuck.get("revision").n()).isEqualTo("1");
        assertThat(version(quoteId).get("phase").s()).as("an alert, not a finalization").isEqualTo("QUOTING");
        assertThat(version(quoteId).get("gsi2pk").s()).isEqualTo("ACTIVE_VERSION");
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("phase").s()).isEqualTo("QUOTING");

        versionReconciler.reconcile(Instant.now());
        Map<String, AttributeValue> seenAgain = issue(QuoteIssues.Type.STUCK_VERSION,
                QuoteIssues.versionTarget(quoteId, 1));
        assertThat(seenAgain.get("revision").n()).isEqualTo("2");
        assertThat(seenAgain.get("firstSeenAt").s()).isEqualTo(stuck.get("firstSeenAt").s());
    }

    @Test
    void consolidatedVersionWhoseSummaryRefreshFailedIsRepairedByTheReconciler() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 2017, true, "2010-01-01");
        step("load-snapshot", quoteId);
        step("evaluate-eligibility", quoteId);
        QuoteTable refreshFails = new QuoteTable(dynamoDb, config, objectMapper) {
            @Override
            void refreshSummary(String quoteId, int version, Instant now) {
                // NOTE: o UpdateItem do resumo falha depois do commit da consolidação e só fica
                // no log
            }
        };

        new WorkflowSteps(refreshFails, objectMapper, config, Clock.systemUTC()).consolidate(quoteId, 1);

        assertThat(version(quoteId).get("phase").s()).isEqualTo("TERMINAL");
        assertThat(version(quoteId).get("gsi2pk").s()).isEqualTo("SUMMARY_PENDING");
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("phase").s()).as("stale summary")
                .isNotEqualTo("TERMINAL");

        assertThat(versionReconciler.reconcile(Instant.now()).get("summariesRepaired")).isPositive();

        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(meta.get("outcome").s()).isEqualTo("INELIGIBLE");
        assertThat(meta.get("offerCount").n()).isEqualTo("0");
        assertThat(version(quoteId)).as("out of SUMMARY_PENDING").doesNotContainKey("gsi2pk");
    }

    @Test
    void summaryReadBeforeTheConsolidationDoesNotDemoteTheTerminalMeta() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 2017, true, "2010-01-01");
        step("load-snapshot", quoteId);
        step("evaluate-eligibility", quoteId);
        Map<String, AttributeValue> beforeConsolidation = version(quoteId);
        step("consolidate", quoteId);
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("phase").s()).isEqualTo("TERMINAL");
        QuoteTable staleRead = new QuoteTable(dynamoDb, config, objectMapper) {
            @Override
            Optional<Map<String, AttributeValue>> get(String pk, String sk) {
                return QuoteKeys.versionSk(1).equals(sk) ? Optional.of(beforeConsolidation) : super.get(pk, sk);
            }
        };

        staleRead.refreshSummary(quoteId, 1, Instant.now());
        staleRead.repairSummary(quoteId, 1, Instant.now());

        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(meta.get("outcome").s()).isEqualTo("INELIGIBLE");
    }

    @Test
    void waiterThatAlwaysFailsToResumeDoesNotBlockTheOthers() throws Exception {
        ResultResumer crashing = new ResultResumer(table, cipher, unreachableSfn(), objectMapper, Clock.systemUTC());
        String poisonQuote = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef poison = insurerRef(poisonQuote, "alpha");
        Task poisonTask = newTask();
        dispatch.handleRequest(insurerRequest(poisonQuote, poisonTask), null);
        assertThatThrownBy(() -> crashing.handle(QuoteFixtures.insurerResult(poison.value(), poisonQuote, "alpha",
                "QUOTED", QuoteFixtures.insurerResultEventId(poison.value())))).isInstanceOf(SdkClientException.class);
        updateWaiter(poison, "SET tokenCiphertext = :unreadable, gsi2sk = :oldest", Map.of(
                ":unreadable", AttributeValue.fromS("unreadable"), ":oldest",
                AttributeValue.fromS("2000-01-01T00:00:00Z")));
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        assertThatThrownBy(() -> crashing.handle(QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha",
                "QUOTED", QuoteFixtures.insurerResultEventId(workRef.value())))).isInstanceOf(SdkClientException.class);
        try {
            waiterReconciler.resumeStale(Instant.now().plusSeconds(60));

            assertThat(waiter(poison).get("status").s()).isEqualTo("RESUMING");
            assertThat(waiter(workRef).get("status").s()).isEqualTo("DONE");
            assertThat(succeededOutput(task).get("status").asText()).isEqualTo("QUOTED");
        } finally {
            updateWaiter(poison, "SET #status = :done REMOVE gsi2pk, gsi2sk", Map.of(":done",
                    AttributeValue.fromS("DONE")));
            sfn.stopExecution(builder -> builder.executionArn(poisonTask.executionArn()));
        }
    }

    @Test
    void versionThatAlwaysFailsToReconcileDoesNotBlockTheOthers() throws Exception {
        String poisonQuote = UUID.randomUUID().toString();
        QuoteFixtures.createQuote(dynamoDb, poisonQuote, QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false,
                "2012-03-15"), Instant.now().minusSeconds(900));
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(poisonQuote)), "sk",
                        AttributeValue.fromS("V#0001")))
                .updateExpression("SET version = :outOfRange, gsi2sk = :oldest")
                .expressionAttributeValues(Map.of(":outOfRange", AttributeValue.fromN("10000"),
                        ":oldest", AttributeValue.fromS("2000-01-01T00:00:00Z"))));
        String quoteId = UUID.randomUUID().toString();
        QuoteFixtures.createQuote(dynamoDb, quoteId, QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false, "2012-03-15"),
                Instant.now().minusSeconds(600));
        try {
            versionReconciler.reconcile(Instant.now());

            assertThat(issue(QuoteIssues.Type.STUCK_VERSION, QuoteIssues.versionTarget(quoteId, 1))).isNotNull();
        } finally {
            dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                    .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(poisonQuote)),
                            "sk", AttributeValue.fromS("V#0001")))
                    .updateExpression("REMOVE gsi2pk, gsi2sk"));
        }
    }

    @Test
    void expiredWaiterWithoutAResultIsTheNormalTimeoutAndOpensNoIssue() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        steps.handleRequest(new StepRequest("record-insurer-outcome", quoteId, 1, "alpha", "TIMED_OUT",
                objectMapper.readTree("{\"Error\":\"States.Timeout\",\"Cause\":\"\"}")), null);
        assertThat(waiter(workRef).get("gsi2pk").s()).isEqualTo("WAITER_EXPIRED");
        assertThat(result(quoteId, "alpha").get("status").s()).as("visible to the user").isEqualTo("TIMED_OUT");

        assertThat(waiterReconciler.reviewExpired(Instant.now())).isZero();

        assertThat(issue(QuoteIssues.Type.TASK_EXPIRED, workRef.value())).isNull();
        assertThat(waiter(workRef).get("status").s()).isEqualTo("EXPIRED");
        assertThat(waiter(workRef)).doesNotContainKey("gsi2pk");
        sfn.stopExecution(builder -> builder.executionArn(task.executionArn()));
    }

    @Test
    void recordedResultThatTheEndedExecutionNeverReceivedOpensTaskExpiredOnce() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        String eventId = QuoteFixtures.insurerResultEventId(workRef.value());
        String body = QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha", "QUOTED", eventId);
        // NOTE: o resume grava o resultado e cai antes do SendTaskSuccess; a execução
        // termina sem recebê-lo
        ResultResumer crashing = new ResultResumer(table, cipher, unreachableSfn(), objectMapper, Clock.systemUTC());
        assertThatThrownBy(() -> crashing.handle(body)).isInstanceOf(SdkClientException.class);
        assertThat(waiter(workRef).get("status").s()).isEqualTo("RESUMING");
        assertThat(waiter(workRef)).containsKey("resumePayload");
        sfn.stopExecution(builder -> builder.executionArn(task.executionArn()));
        await().atMost(Duration.ofSeconds(15)).until(() -> execution(task).status() == ExecutionStatus.ABORTED);
        updateWaiter(workRef, "SET #status = :expired, gsi2pk = :index, gsi2sk = :now", Map.of(
                ":expired", AttributeValue.fromS("EXPIRED"), ":index", AttributeValue.fromS("WAITER_EXPIRED"),
                ":now", AttributeValue.fromS(Instant.now().toString())));

        assertThat(waiterReconciler.reviewExpired(Instant.now())).isPositive();

        Map<String, AttributeValue> issue = issue(QuoteIssues.Type.TASK_EXPIRED, workRef.value());
        assertThat(issue.get("type").s()).as("the item type (ADR 0015)").isEqualTo("ISSUE");
        assertThat(issue.get("issueType").s()).isEqualTo("TASK_EXPIRED");
        assertThat(issue.get("quoteId").s()).isEqualTo(quoteId);
        assertThat(issue.get("organizationId").s()).isEqualTo("org-acme");
        assertThat(issue.get("safeSummary").s()).contains("alpha");
        assertThat(waiter(workRef)).doesNotContainKey("gsi2pk");
        waiterReconciler.reviewExpired(Instant.now());
        assertThat(issue(QuoteIssues.Type.TASK_EXPIRED, workRef.value()).get("revision").n()).isEqualTo("1");
    }

    /** Roda a ação capturando as mensagens de log da classe. */
    private static <T> T logging(Class<?> source, List<String> messages, Supplier<T> action) {
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record instanceof ExtLogRecord extended ? extended.getFormattedMessage()
                        : record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(source.getName());
        logger.addHandler(capture);
        try {
            return action.get();
        } finally {
            logger.removeHandler(capture);
        }
    }

    private void updateWaiter(WorkRef workRef, String expression, Map<String, AttributeValue> values) {
        Map<String, String> names = expression.contains("#status") ? Map.of("#status", "status") : null;
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.waiterPk(workRef)), "sk",
                        AttributeValue.fromS("META")))
                .updateExpression(expression)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values));
    }

    @Test
    void expiredWaiterWhoseResultWasDeliveredDoesNotOpenTaskExpired() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        String eventId = QuoteFixtures.insurerResultEventId(workRef.value());
        String body = QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha", "QUOTED", eventId);
        assertThat(resumer.handle(body)).isEqualTo(ResultResumer.Disposition.RESUMED);
        assertThat(succeededOutput(task).get("status").asText()).isEqualTo("QUOTED");
        // NOTE: o resume cai depois do SendTaskSuccess aceito e antes do DONE; a
        // reentrega marca EXPIRED (§8)
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.waiterPk(workRef)), "sk",
                        AttributeValue.fromS("META")))
                .updateExpression("SET #status = :resuming, gsi2pk = :index, gsi2sk = :now")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":resuming", AttributeValue.fromS("RESUMING"),
                        ":index", AttributeValue.fromS("WAITER_RESUMING"),
                        ":now", AttributeValue.fromS(Instant.now().toString()))));

        assertThat(resumer.handle(body)).isEqualTo(ResultResumer.Disposition.RESUMED);
        assertThat(waiter(workRef).get("status").s()).isEqualTo("EXPIRED");
        assertThat(waiter(workRef).get("gsi2pk").s()).isEqualTo("WAITER_EXPIRED");
        assertThat(result(quoteId, "alpha").get("resultEventId").s()).isEqualTo(eventId);

        waiterReconciler.reviewExpired(Instant.now());

        assertThat(issue(QuoteIssues.Type.TASK_EXPIRED, workRef.value())).isNull();
        assertThat(waiter(workRef)).doesNotContainKey("gsi2pk");
    }

    @Test
    void t31_resultThatKeepsFailingReachesTheDlqAndItsReprocessingDoesNotDuplicateIt() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        String eventId = QuoteFixtures.insurerResultEventId(workRef.value());
        String body = QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha", "QUOTED", eventId);
        sqs.sendMessage(builder -> builder.queueUrl(resultsQueueUrl).messageBody(body));
        // NOTE: o consumidor falha sem apagar; o recebimento seguinte passa do
        // maxReceiveCount e a RedrivePolicy move
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            sqs.receiveMessage(builder -> builder.queueUrl(resultsQueueUrl).maxNumberOfMessages(10).waitTimeSeconds(0));
            return visibleMessages(deadLetterQueueUrl) > 0;
        });

        assertThat(deadLetters.reprocess(Instant.now()).get("redriven")).isPositive();

        Message redriven = await().atMost(Duration.ofSeconds(15)).until(() -> sqs.receiveMessage(builder -> builder
                .queueUrl(resultsQueueUrl).maxNumberOfMessages(1).waitTimeSeconds(1).messageAttributeNames("All"))
                .messages().stream().findFirst().orElse(null), message -> message != null);
        assertThat(redriven.body()).isEqualTo(body);
        assertThat(redriven.messageAttributes().get("dlqAttempt").stringValue()).isEqualTo("2");
        assertThat(resume.handleRequest(QuoteFixtures.sqs(redriven.body()), null).batchItemFailures()).isEmpty();
        sqs.deleteMessage(builder -> builder.queueUrl(resultsQueueUrl).receiptHandle(redriven.receiptHandle()));
        assertThat(succeededOutput(task).get("status").asText()).isEqualTo("QUOTED");
        assertThat(resumer.handle(body)).as("reprocessed again").isEqualTo(ResultResumer.Disposition.DISCARDED);
        assertThat(offers(quoteId)).hasSize(1);
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).as("no new external call").hasSize(1);

        sqs.sendMessage(builder -> builder.queueUrl(deadLetterQueueUrl).messageBody(body)
                .messageAttributes(Map.of("dlqAttempt", MessageAttributeValue.builder().dataType("Number")
                        .stringValue("3").build())));
        assertThat(deadLetters.reprocess(Instant.now()).get("discarded")).isPositive();
        Map<String, AttributeValue> deadLetter = issue(QuoteIssues.Type.DEAD_LETTER, eventId);
        assertThat(deadLetter.get("quoteId").s()).isEqualTo(quoteId);
        assertThat(deadLetter.get("queryId").s()).isEqualTo(QuoteFixtures.providerQueryId(workRef.value()));
        assertThat(deadLetter.get("organizationId").s()).isEqualTo("org-acme");
        assertThat(deadLetter.get("safeSummary").s()).contains("tentativa 3").doesNotContain("2450.90");
        assertThat(visibleMessages(deadLetterQueueUrl)).isZero();
    }

    @Test
    void t31_resultReprocessedAfterTheVersionDeadlineGoesBackToTheResumeAndIsKeptAsLate() throws Exception {
        String quoteId = quoteReadyToQuote(Usage.COMMERCIAL);
        WorkRef workRef = insurerRef(quoteId, "alpha");
        Task task = newTask();
        dispatch.handleRequest(insurerRequest(quoteId, task), null);
        steps.handleRequest(new StepRequest("record-insurer-outcome", quoteId, 1, "alpha", "TIMED_OUT",
                objectMapper.readTree("{\"Error\":\"States.Timeout\",\"Cause\":\"\"}")), null);
        step("consolidate", quoteId);
        sfn.stopExecution(builder -> builder.executionArn(task.executionArn()));
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)), "sk",
                        AttributeValue.fromS("V#0001")))
                .updateExpression("SET deadlineAt = :past")
                .expressionAttributeValues(Map.of(":past", AttributeValue.fromS(Instant.now().minusSeconds(60)
                        .toString()))));
        String eventId = QuoteFixtures.insurerResultEventId(workRef.value());
        String body = QuoteFixtures.insurerResult(workRef.value(), quoteId, "alpha", "QUOTED", eventId);
        sqs.sendMessage(builder -> builder.queueUrl(deadLetterQueueUrl).messageBody(body));

        assertThat(deadLetters.reprocess(Instant.now()).get("redriven")).as("the version deadline does not count")
                .isPositive();

        Message redriven = await().atMost(Duration.ofSeconds(15)).until(() -> sqs.receiveMessage(builder -> builder
                .queueUrl(resultsQueueUrl).maxNumberOfMessages(1).waitTimeSeconds(1).messageAttributeNames("All"))
                .messages().stream().filter(message -> message.body().equals(body)).findFirst().orElse(null),
                message -> message != null);
        assertThat(redriven.messageAttributes().get("dlqAttempt").stringValue()).isEqualTo("2");
        assertThat(resume.handleRequest(QuoteFixtures.sqs(redriven.body()), null).batchItemFailures()).isEmpty();
        sqs.deleteMessage(builder -> builder.queueUrl(resultsQueueUrl).receiptHandle(redriven.receiptHandle()));
        assertThat(lateItems(quoteId)).singleElement().satisfies(late -> {
            assertThat(late.get("reason").s()).isEqualTo("LATE");
            assertThat(late.get("eventId").s()).isEqualTo(eventId);
        });
        assertThat(issue(QuoteIssues.Type.DEAD_LETTER, eventId)).isNull();
        assertThat(resumer.handle(body)).as("reprocessed again").isEqualTo(ResultResumer.Disposition.DISCARDED);
        assertThat(lateItems(quoteId)).hasSize(1);
        assertThat(outboxOf(quoteId, QuoteEvents.INSURER_QUOTE_REQUESTED)).as("no new external call").hasSize(1);
    }

    @Test
    void reconcilerRunsEveryDutyAndReportsIt() {
        Map<String, Object> report = reconciler.handleRequest(Map.of("source", "test"), null);

        assertThat(report).containsKeys("republished", "resumed", "versions", "expiredTasks", "deadLetters",
                "outboxPendingAgeSeconds");
        assertThat(report.keySet()).noneMatch(key -> key.endsWith("Error"));
    }

    @Test
    void issueProjectorProjectsTheProviderIssueIdempotentlyByEventIdAndRevision() throws Exception {
        String issueId = "is_" + UUID.randomUUID().toString().replace("-", "");
        String queryId = "q_" + UUID.randomUUID().toString().replace("-", "");
        String raised = providerEvent("OperationIssueRaised", Map.of("issueId", issueId, "queryId", queryId));
        String updated = providerEvent("OperationIssueUpdated", Map.of("issueId", issueId, "queryId", queryId));

        assertThat(issueProjector.handleRequest(QuoteFixtures.sqs(raised), null).batchItemFailures()).isEmpty();

        Map<String, AttributeValue> issue = item(QuoteKeys.issuePk(issueId), "META");
        assertThat(issue.get("type").s()).as("the item type (ADR 0015)").isEqualTo("ISSUE");
        assertThat(issue.get("issueType").s()).as("the event type").isEqualTo("UNKNOWN_QUERY");
        assertThat(issue.get("status").s()).isEqualTo("OPEN");
        assertThat(issue.get("source").s()).isEqualTo("provider");
        assertThat(issue.get("reconcilable").bool()).isTrue();
        assertThat(issue.get("revision").n()).isEqualTo("1");
        assertThat(issue.get("gsi1pk").s()).isEqualTo("ISSUES#org-acme");
        assertThat(issue.get("gsi1sk").s()).isEqualTo(issue.get("lastSeenAt").s() + "#" + issueId);
        assertThat(issue).as("only the quote's own issues are reviewed").doesNotContainKey("gsi2pk");
        assertThat(item(QuoteKeys.queryRefPk(queryId), "META").get("organizationId").s()).isEqualTo("org-acme");

        assertThat(issueProjectorLogic.handle(raised)).as("the same event")
                .isEqualTo(IssueProjector.Disposition.DISCARDED);
        assertThat(issueProjectorLogic.handle(updated)).isEqualTo(IssueProjector.Disposition.PROJECTED);
        Map<String, AttributeValue> resolved = item(QuoteKeys.issuePk(issueId), "META");
        assertThat(resolved.get("status").s()).isEqualTo("RESOLVED");
        assertThat(resolved.get("revision").n()).isEqualTo("2");
        assertThat(resolved.get("firstSeenAt").s()).isEqualTo(issue.get("firstSeenAt").s());
        assertThat(resolved.get("gsi1sk").s()).isNotEqualTo(issue.get("gsi1sk").s());

        String republished = withEventId(raised, UUID.randomUUID().toString());
        assertThat(issueProjectorLogic.handle(republished)).as("an older revision")
                .isEqualTo(IssueProjector.Disposition.DISCARDED);
        assertThat(item(QuoteKeys.issuePk(issueId), "META").get("status").s()).isEqualTo("RESOLVED");
    }

    @Test
    void queryReconciledUpdatesTheOperationOfItsOrganizationOnce() throws Exception {
        String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
        String queryId = "q_" + UUID.randomUUID().toString().replace("-", "");
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(Map.of(
                "pk", AttributeValue.fromS(QuoteKeys.operationPk(operationId)), "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("OPERATION"), "operationId", AttributeValue.fromS(operationId),
                "organizationId", AttributeValue.fromS("org-acme"), "queryId", AttributeValue.fromS(queryId),
                "status", AttributeValue.fromS("QUEUED"), "reason", AttributeValue.fromS("motivo do operador"),
                "revision", AttributeValue.fromN("0"))));
        String failed = providerEvent("QueryReconciled", "failed", Map.of("operationId", operationId,
                "queryId", queryId));

        assertThat(issueProjectorLogic.handle(failed)).isEqualTo(IssueProjector.Disposition.PROJECTED);

        Map<String, AttributeValue> operation = item(QuoteKeys.operationPk(operationId), "META");
        assertThat(operation.get("status").s()).isEqualTo("FAILED");
        assertThat(operation.get("revision").n()).isEqualTo("2");
        assertThat(operation.get("reason").s()).as("the audit stays").isEqualTo("motivo do operador");
        assertThat(operation.get("safeSummary").s()).contains("UNKNOWN");
        assertThat(operation.get("updatedAt").s()).isEqualTo(Instant.parse(
                objectMapper.readTree(failed).at("/detail/occurredAt").asText()).toString());
        assertThat(item(QuoteKeys.queryRefPk(queryId), "META"))
                .as("the pair of a QueryReconciled is the request's; only an issue makes the query known").isNull();
        assertThat(issueProjectorLogic.handle(failed)).isEqualTo(IssueProjector.Disposition.DISCARDED);

        String foreign = providerEvent("QueryReconciled", "succeeded", Map.of("operationId", operationId,
                "queryId", queryId, "organizationId", "org-globex", "eventId", UUID.randomUUID().toString()));
        assertThat(issueProjectorLogic.handle(withRevision(foreign, 3))).as("another organization")
                .isEqualTo(IssueProjector.Disposition.FOREIGN_ORGANIZATION);
        assertThat(item(QuoteKeys.operationPk(operationId), "META").get("status").s()).isEqualTo("FAILED");
        assertThat(item(QuoteKeys.queryRefPk(queryId), "META")).isNull();
    }

    @Test
    void malformedAndForeignIssueEventsAreNotProjected() throws Exception {
        String raised = providerEvent("OperationIssueRaised", Map.of("issueId", "is_" + "0".repeat(32)));
        ObjectNode fromQuote = (ObjectNode) objectMapper.readTree(raised);
        fromQuote.put("source", "mmda.quote");
        ObjectNode otherType = (ObjectNode) objectMapper.readTree(raised);
        otherType.put("detail-type", "EnrichmentResolved");
        ObjectNode withoutIssue = (ObjectNode) objectMapper.readTree(raised);
        ((ObjectNode) withoutIssue.get("detail")).remove("issueId");
        ObjectNode quoteType = (ObjectNode) objectMapper.readTree(raised);
        ((ObjectNode) quoteType.get("detail")).put("type", "STUCK_VERSION");

        assertThat(issueProjectorLogic.handle(fromQuote.toString())).isEqualTo(IssueProjector.Disposition.IGNORED);
        assertThat(issueProjectorLogic.handle(otherType.toString())).isEqualTo(IssueProjector.Disposition.IGNORED);
        for (String malformed : List.of(withoutIssue.toString(), quoteType.toString(), "not json")) {
            assertThat(issueProjector.handleRequest(QuoteFixtures.sqs(malformed), null).batchItemFailures())
                    .as(malformed).hasSize(1);
        }
        assertThat(item(QuoteKeys.issuePk("is_" + "0".repeat(32)), "META")).isNull();
    }

    @Test
    void issuesDlqIsRedrivenBelowTheLimitAndDiscardedAtItWithTheEventOrganization() throws Exception {
        String issueId = "is_" + UUID.randomUUID().toString().replace("-", "");
        String queryId = "q_" + UUID.randomUUID().toString().replace("-", "");
        String body = providerEvent("OperationIssueRaised", Map.of("issueId", issueId, "queryId", queryId,
                "eventId", UUID.randomUUID().toString()));
        sqs.sendMessage(builder -> builder.queueUrl(issuesDeadLetterQueueUrl).messageBody(body));

        assertThat(deadLetters.reprocessIssues(Instant.now()).get("redriven")).isEqualTo(1);

        Message redriven = await().atMost(Duration.ofSeconds(15)).until(() -> sqs.receiveMessage(builder -> builder
                .queueUrl(issuesQueueUrl).maxNumberOfMessages(1).waitTimeSeconds(1).messageAttributeNames("All"))
                .messages().stream().findFirst().orElse(null), message -> message != null);
        assertThat(redriven.messageAttributes().get("dlqAttempt").stringValue()).isEqualTo("2");
        assertThat(issueProjector.handleRequest(QuoteFixtures.sqs(redriven.body()), null).batchItemFailures())
                .isEmpty();
        sqs.deleteMessage(builder -> builder.queueUrl(issuesQueueUrl).receiptHandle(redriven.receiptHandle()));
        assertThat(item(QuoteKeys.issuePk(issueId), "META").get("issueType").s()).isEqualTo("UNKNOWN_QUERY");

        sqs.sendMessage(builder -> builder.queueUrl(issuesDeadLetterQueueUrl).messageBody(body)
                .messageAttributes(Map.of("dlqAttempt", MessageAttributeValue.builder().dataType("Number")
                        .stringValue("3").build())));
        sqs.sendMessage(builder -> builder.queueUrl(issuesDeadLetterQueueUrl).messageBody("not json"));
        assertThat(deadLetters.reprocessIssues(Instant.now()).get("discarded")).isEqualTo(2);

        String eventId = objectMapper.readTree(body).at("/detail/eventId").asText();
        Map<String, AttributeValue> deadLetter = issue(QuoteIssues.Type.DEAD_LETTER, eventId);
        assertThat(deadLetter.get("organizationId").s()).isEqualTo("org-acme");
        assertThat(deadLetter.get("queryId").s()).isEqualTo(queryId);
        assertThat(deadLetter.get("safeSummary").s()).contains("issues-dlq", "tentativa 3");
        assertThat(deadLetter).as("a discarded message is a fact, not a situation that goes away")
                .doesNotContainKey("gsi2pk");
        assertThat(visibleMessages(issuesDeadLetterQueueUrl)).isZero();
        assertThat(visibleMessages(deadLetterQueueUrl)).as("the results-dlq is another queue").isZero();
    }

    @Test
    void stuckVersionIsResolvedOnceTheVersionLeavesTheActiveIndex() throws Exception {
        String quoteId = UUID.randomUUID().toString();
        QuoteFixtures.createQuote(dynamoDb, quoteId, QuoteFixtures.snapshot(Usage.PERSONAL, 2021, false, "2012-03-15"),
                Instant.now().minusSeconds(600));
        versionReconciler.reconcile(Instant.now());
        Map<String, AttributeValue> stuck = issue(QuoteIssues.Type.STUCK_VERSION,
                QuoteIssues.versionTarget(quoteId, 1));
        assertThat(stuck.get("gsi2pk").s()).isEqualTo("ISSUE_OPEN");

        issueReviewer.review(Instant.now());
        assertThat(issue(QuoteIssues.Type.STUCK_VERSION, QuoteIssues.versionTarget(quoteId, 1)).get("status").s())
                .as("the version is still active").isEqualTo("OPEN");

        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)), "sk",
                        AttributeValue.fromS("V#0001")))
                .updateExpression("SET phase = :terminal, outcome = :outcome REMOVE gsi2pk, gsi2sk")
                .expressionAttributeValues(Map.of(":terminal", AttributeValue.fromS("TERMINAL"),
                        ":outcome", AttributeValue.fromS("TECHNICAL_FAILURE"))));
        Instant reviewedAt = Instant.now();
        assertThat(issueReviewer.review(reviewedAt).get("resolved")).isPositive();

        Map<String, AttributeValue> resolved = issue(QuoteIssues.Type.STUCK_VERSION,
                QuoteIssues.versionTarget(quoteId, 1));
        assertThat(resolved.get("status").s()).isEqualTo("RESOLVED");
        assertThat(resolved.get("revision").n())
                .isEqualTo(Long.toString(Long.parseLong(stuck.get("revision").n()) + 1));
        assertThat(resolved.get("firstSeenAt").s()).isEqualTo(stuck.get("firstSeenAt").s());
        assertThat(resolved.get("lastSeenAt").s()).isEqualTo(reviewedAt.toString());
        assertThat(resolved.get("gsi1sk").s()).isEqualTo(reviewedAt + "#" + resolved.get("issueId").s());
        assertThat(resolved).containsKey("resolvedAt").doesNotContainKey("gsi2pk");

        versionReconciler.reconcile(Instant.now());
        assertThat(issue(QuoteIssues.Type.STUCK_VERSION, QuoteIssues.versionTarget(quoteId, 1)).get("status").s())
                .as("a terminal version is not reopened").isEqualTo("RESOLVED");
    }

    @Test
    void staleSummaryReadDoesNotDemoteAnyPhaseNorTheOfferCount() throws Exception {
        String quoteId = newQuote(Usage.PERSONAL, 2021, false, "2012-03-15");
        step("load-snapshot", quoteId);
        Map<String, AttributeValue> validating = version(quoteId);
        assertThat(validating.get("phase").s()).isEqualTo("VALIDATING");
        setVersionPhase(quoteId, "QUOTING");
        table.refreshSummary(quoteId, 1, Instant.now());
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("phase").s()).isEqualTo("QUOTING");
        QuoteTable staleRead = new QuoteTable(dynamoDb, config, objectMapper) {
            @Override
            Optional<Map<String, AttributeValue>> get(String pk, String sk) {
                return QuoteKeys.versionSk(1).equals(sk) ? Optional.of(validating) : super.get(pk, sk);
            }
        };

        staleRead.refreshSummary(quoteId, 1, Instant.now());
        staleRead.repairSummary(quoteId, 1, Instant.now());
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("phase").s()).as("VALIDATING over QUOTING")
                .isEqualTo("QUOTING");

        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET offerCount = :two")
                .expressionAttributeValues(Map.of(":two", AttributeValue.fromN("2"))));
        QuoteTable staleCount = new QuoteTable(dynamoDb, config, objectMapper) {
            @Override
            int count(String pk, String prefix) {
                return 1;
            }
        };
        staleCount.refreshSummary(quoteId, 1, Instant.now());
        assertThat(item(QuoteKeys.quotePk(quoteId), "META").get("offerCount").n()).isEqualTo("2");

        setVersionPhase(quoteId, "TERMINAL");
        QuoteTable moreOffers = new QuoteTable(dynamoDb, config, objectMapper) {
            @Override
            int count(String pk, String prefix) {
                return 3;
            }
        };
        moreOffers.refreshSummary(quoteId, 1, Instant.now());
        Map<String, AttributeValue> meta = item(QuoteKeys.quotePk(quoteId), "META");
        assertThat(meta.get("phase").s()).isEqualTo("TERMINAL");
        assertThat(meta.get("offerCount").n()).isEqualTo("3");
    }

    @Test
    void reconcilerRunsTheM5DutiesAndReportsThem() {
        Map<String, Object> report = reconciler.handleRequest(Map.of("source", "test"), null);

        assertThat(report).containsKeys("issues", "issueDeadLetters");
        assertThat(report.keySet()).noneMatch(key -> key.endsWith("Error"));
    }

    private void setVersionPhase(String quoteId, String phase) {
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)), "sk",
                        AttributeValue.fromS("V#0001")))
                .updateExpression("SET phase = :phase")
                .expressionAttributeValues(Map.of(":phase", AttributeValue.fromS(phase))));
    }

    private String providerEvent(String detailType, Map<String, String> overrides) throws Exception {
        return providerEvent(detailType, null, overrides);
    }

    /**
     * Exemplo do AsyncAPI da provider, pelo nome ou o primeiro, com os campos
     * trocados para o teste não colidir
     * com outro.
     */
    private String providerEvent(String detailType, String example, Map<String, String> overrides) throws Exception {
        JsonNode asyncApi = objectMapper.valueToTree(new Yaml().load(
                Files.readString(Path.of("../../contracts/provider-solution/asyncapi.yaml"))));
        JsonNode examples = asyncApi.at("/components/messages/" + detailType + "/examples");
        JsonNode chosen = examples.get(0);
        for (JsonNode candidate : examples) {
            if (candidate.path("name").asText().equals(example)) {
                chosen = candidate;
            }
        }
        ObjectNode payload = (ObjectNode) chosen.get("payload").deepCopy();
        ObjectNode detail = (ObjectNode) payload.get("detail");
        detail.put("eventId", UUID.randomUUID().toString());
        overrides.forEach(detail::put);
        return payload.toString();
    }

    private String withEventId(String body, String eventId) throws Exception {
        ObjectNode envelope = (ObjectNode) objectMapper.readTree(body);
        ((ObjectNode) envelope.get("detail")).put("eventId", eventId);
        return envelope.toString();
    }

    private String withRevision(String body, int revision) throws Exception {
        ObjectNode envelope = (ObjectNode) objectMapper.readTree(body);
        ((ObjectNode) envelope.get("detail")).put("revision", revision);
        return envelope.toString();
    }

    /** Execução encerrada no estado pedido, gravada como a execução da versão 1. */
    private void attachExecution(String quoteId, String machineArn, ExecutionStatus expected) {
        String executionArn = sfn.startExecution(builder -> builder.stateMachineArn(machineArn)
                .name(QuoteKeys.executionName(quoteId, 1)).input("{}")).executionArn();
        if (expected == ExecutionStatus.ABORTED) {
            sfn.stopExecution(builder -> builder.executionArn(executionArn));
        }
        await().atMost(Duration.ofSeconds(20))
                .until(() -> sfn.describeExecution(builder -> builder.executionArn(executionArn)).status() == expected);
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS(QuoteKeys.quotePk(quoteId)), "sk",
                        AttributeValue.fromS("V#0001")))
                .updateExpression("SET executionArn = :arn")
                .expressionAttributeValues(Map.of(":arn", AttributeValue.fromS(executionArn))));
    }

    /**
     * Step Functions que não responde, como o {@code resume} que cai depois do
     * commit e antes da retomada.
     */
    private static SfnClient unreachableSfn() {
        return (SfnClient) Proxy.newProxyInstance(SfnClient.class.getClassLoader(), new Class<?>[] { SfnClient.class },
                (proxy, method, args) -> {
                    if ("sendTaskSuccess".equals(method.getName())) {
                        throw SdkClientException.create("connection reset after the commit");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private int visibleMessages(String queueUrl) {
        return Integer.parseInt(sqs.getQueueAttributes(builder -> builder.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
    }

    private Map<String, AttributeValue> issue(QuoteIssues.Type type, String target) {
        return item(QuoteKeys.issuePk(QuoteIssues.issueId(type, target)), "META");
    }

    private String newQuote(Usage usage, int year, boolean salvage, String licenseDate) throws Exception {
        String quoteId = UUID.randomUUID().toString();
        QuoteFixtures.createQuote(dynamoDb, quoteId, QuoteFixtures.snapshot(usage, year, salvage, licenseDate),
                Instant.now());
        return quoteId;
    }

    /**
     * Cotação elegível, na fase {@code ENRICHING}, pronta para o pedido de
     * enriquecimento.
     */
    private String eligibleQuote(Usage usage) throws Exception {
        String quoteId = newQuote(usage, 2021, false, "2012-03-15");
        step("load-snapshot", quoteId);
        assertThat(step("evaluate-eligibility", quoteId).get("decision")).isEqualTo("ELIGIBLE");
        return quoteId;
    }

    /**
     * Leva a cotação até o {@code select-insurers}, com o enriquecimento resolvido
     * por um token real.
     */
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

    /**
     * Um item do Map por seguradora, cada um com o próprio task token, como a
     * máquina os despacha.
     */
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

    /**
     * Resultado da seguradora pela fila {@code results}, com o {@code eventId}
     * determinístico da provider.
     */
    private void resumeInsurer(String quoteId, String insurerId, String status) {
        WorkRef workRef = insurerRef(quoteId, insurerId);
        LambdaEvents.BatchResponse response = resume.handleRequest(QuoteFixtures.sqs(QuoteFixtures.insurerResult(
                workRef.value(), quoteId, insurerId, status, QuoteFixtures.insurerResultEventId(workRef.value()))),
                null);
        assertThat(response.batchItemFailures()).isEmpty();
        assertThat(result(quoteId, insurerId).get("status").s()).isEqualTo(status);
    }

    private DispatchRequest enrichmentRequest(String quoteId, Task task) {
        return new DispatchRequest(WorkRef.Kind.ENRICHMENT, quoteId, 1, null, task.executionArn(), task.token(), 20);
    }

    private record Task(String token, String executionArn) {
    }

    /**
     * Inicia a {@code token-machine} e lê o task token real que ela envia à fila.
     */
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
                        ":quoteId", AttributeValue.fromS(quoteId))))
                .items()).isEmpty();
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
                        ":prefix", AttributeValue.fromS(prefix))))
                .items();
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

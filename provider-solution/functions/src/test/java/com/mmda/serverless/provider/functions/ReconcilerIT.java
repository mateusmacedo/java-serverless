package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.sqsEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.provider.api.CallbackApi;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryKeys;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.IssueType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deveres do {@code reconciler} da provider no Floci (desenho, §9; PRD, T14 e
 * T16), inclusive a pendência do M2.
 * Nenhum caso repete a chamada ao fornecedor: o {@link ProviderStub} conta as
 * chamadas e as leituras da situação, e
 * o {@link TestClock} vence prazos e leases sem espera real.
 */
class ReconcilerIT extends FlociIntegrationTest {

    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration PAST_EVERY_LIVE_DELIVERY = Duration.ofSeconds(60);
    private static final String BETA = "beta";
    private static final List<String> METRIC_LINES = new CopyOnWriteArrayList<>();

    @TestBean
    private Clock clock;

    @TestBean
    private Metrics.MetricsSink metricsSink;

    @Autowired
    private InsurerWorkerFunction insurerWorker;

    @Autowired
    private EnrichmentWorkerFunction enrichmentWorker;

    @Autowired
    private QueryReconciliation queries;

    @Autowired
    private FanOutRecovery fanOuts;

    @Autowired
    private InboxReconciliation inbox;

    @Autowired
    private OutboxReconciliation outbox;

    @Autowired
    private IssueReview issueReview;

    @Autowired
    private ReconcilerFunction reconciler;

    @Autowired
    private QueryLedger ledger;

    @Autowired
    private QueryKeySecret queryKeySecret;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private S3Client s3;

    private CallbackApi api;

    static Clock clock() {
        return TestClock.INSTANCE;
    }

    static Metrics.MetricsSink metricsSink() {
        return METRIC_LINES::add;
    }

    @BeforeEach
    void callbackApi() {
        api = new CallbackApi(DYNAMO_DB, s3, objectMapper, TABLE, BUCKET);
    }

    @AfterEach
    void resetClock() {
        TestClock.INSTANCE.reset();
    }

    @Test
    void t14_acceptedQueryWithoutCallbackIsConcludedByTheStatusApiWithoutANewCall() {
        TestRequests.Request request = acceptedBetaQuery("vehicle-beta-silent");
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        PROVIDERS.betaStatus(providerRequestId, status(providerRequestId, request.queryId(), "COMPLETED", offer()));

        assertThat(queries.reconcile(request.queryId()).outcome()).as("within the deadline")
                .isEqualTo(QueryReconciliation.Outcome.IN_PROGRESS);
        assertThat(PROVIDERS.statusReads(providerRequestId)).isZero();
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);

        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.CONCLUDED);

        assertThat(PROVIDERS.calls(request.queryId())).as("the quote request is never repeated").isEqualTo(1);
        assertThat(PROVIDERS.statusReads(providerRequestId)).isEqualTo(1);
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("COMPLETED");
        assertThat(query).doesNotContainKeys("gsi2pk", "gsi2sk");
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
            assertThat(attribute(attempt, "outcome")).contains("QUOTED");
            assertThat(attribute(attempt, "finishedAt")).isPresent();
        });
        assertThat(queryItems(request.queryId(), "EVIDENCE#")).singleElement().satisfies(evidence -> {
            assertThat(attribute(evidence, "evidenceId")).contains(Identities.evidenceId(request.queryId(), 1));
            assertThat(attribute(evidence, "rawResponseRef"))
                    .contains("raw/" + request.queryId() + "/status-0001.json");
        });
        JsonNode detail = insurerResult(request.queryId());
        assertThat(detail.path("status").asString()).isEqualTo("QUOTED");
        assertThat(detail.path("attemptRef").asString()).isEqualTo(Identities.attemptRef(request.queryId(), 1));
        assertThat(detail.path("offer").path("insurerName").asString()).isEqualTo("Beta Seguros");
        assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.SETTLED);
        assertThat(PROVIDERS.statusReads(providerRequestId)).isEqualTo(1);
    }

    @Test
    void t16_callbackAfterTheReconciledConclusionIsKeptAsLateEvidenceAndReopensNothing() {
        TestRequests.Request request = acceptedBetaQuery("vehicle-beta-late");
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        PROVIDERS.betaStatus(providerRequestId, status(providerRequestId, request.queryId(), "COMPLETED", offer()));
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);
        queries.reconcile(request.queryId());
        Map<String, AttributeValue> published = item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()),
                "META");
        String externalEventId = "bev_" + UUID.randomUUID();

        String status = api.receive(BETA, callback(externalEventId, providerRequestId, request.queryId(),
                "{\"outcome\":\"DECLINED\",\"declineCode\":\"RISK_NOT_ACCEPTED\"}"));

        assertThat(status).isEqualTo("PROCESSED");
        Map<String, AttributeValue> late = item("INBOX#beta#" + externalEventId, "META");
        assertThat(attribute(late, "processingStatus")).contains("PROCESSED");
        assertThat(attribute(late, "payload")).as("the late callback is kept as evidence")
                .hasValueSatisfying(payload -> assertThat(payload).contains("RISK_NOT_ACCEPTED"));
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
        assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META"))
                .as("the published result is not replaced").isEqualTo(published);
        assertThat(insurerResult(request.queryId()).path("status").asString()).isEqualTo("QUOTED");
    }

    @Test
    void t16_callbackAfterTheDeadlineConcludesTheUnknownQueryWithItsOwnResult() {
        TestRequests.Request request = acceptedBetaQuery("vehicle-beta-late");
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        PROVIDERS.betaStatus(providerRequestId, status(providerRequestId, request.queryId(), "PROCESSING", null));
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);

        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.UNKNOWN);
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "gsi2pk")).contains("QUERY_UNKNOWN");
        assertThat(issue(IssueType.UNKNOWN_QUERY, request.queryId())).as("the insurer is still processing").isEmpty();

        String status = api.receive(BETA, callback("bev_" + UUID.randomUUID(), providerRequestId, request.queryId(),
                offer()));

        assertThat(status).isEqualTo("PROCESSED");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(insurerResult(request.queryId()).path("status").asString()).isEqualTo("QUOTED");
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
    }

    /**
     * A entrega que gravou o {@code 202} caiu antes da metade do worker no
     * {@code CORR#}, com o callback retido.
     */
    @Test
    void expiredAcceptedQueryRedoesTheWorkerHalfAndIsConcludedByTheHeldCallbackWithoutReadingTheStatus() {
        TestRequests.Request request = TestRequests.insurer("vehicle-lost-half", deadline(), BETA);
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        givenAcceptedWithoutTheWorkerHalf(request, providerRequestId);
        String externalEventId = "bev_" + UUID.randomUUID();
        assertThat(api.receive(BETA, callback(externalEventId, providerRequestId, null, offer())))
                .as("held until the worker half").isEqualTo("RECEIVED");
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);

        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.CONCLUDED);

        assertThat(PROVIDERS.statusReads(providerRequestId)).isZero();
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(attribute(item("INBOX#beta#" + externalEventId, "META"), "processingStatus")).contains("PROCESSED");
        assertThat(attribute(item("CORR#beta#" + providerRequestId, "META"), "queryId")).contains(request.queryId());
        assertThat(insurerResult(request.queryId()).path("status").asString()).isEqualTo("QUOTED");
    }

    @Test
    void unknownQueryWithoutStatusApiOpensOneReconcilableIssueThatResolvesWhenTheQueryConcludes() {
        TestRequests.Request request = TestRequests.insurer(ProviderStub.VEHICLE_SLOW, deadline());
        insurerWorker.apply(sqsEvent(request.body()));
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("UNKNOWN");
        String issueId = Identities.issueId(IssueType.UNKNOWN_QUERY, request.queryId());

        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.UNKNOWN);
        queries.reconcile(request.queryId());

        Map<String, AttributeValue> open = item("ISSUE#" + issueId, "META");
        assertThat(attribute(open, "status")).contains("OPEN");
        assertThat(attribute(open, "revision")).as("observing the same problem again changes nothing").contains("1");
        assertThat(attribute(open, "gsi2pk")).contains("ISSUE_OPEN");
        assertThat(open.get("reconcilable").bool()).isTrue();
        JsonNode raised = issueEvent(issueId, 1, "OperationIssueRaised");
        assertThat(raised.path("type").asString()).isEqualTo("UNKNOWN_QUERY");
        assertThat(raised.path("organizationId").asString()).isEqualTo("org-acme");
        assertThat(raised.path("queryId").asString()).isEqualTo(request.queryId());
        assertThat(raised.path("quoteId").asString()).isEqualTo(request.quoteId());
        assertThat(raised.path("reconcilable").asBoolean()).isTrue();
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
        givenQuerySettledByAnotherPath(request.queryId());

        issueReview.run(500, TestClock.INSTANCE.instant());

        Map<String, AttributeValue> resolved = item("ISSUE#" + issueId, "META");
        assertThat(attribute(resolved, "status")).contains("RESOLVED");
        assertThat(attribute(resolved, "revision")).contains("2");
        assertThat(resolved).doesNotContainKey("gsi2pk");
        JsonNode updated = issueEvent(issueId, 2, "OperationIssueUpdated");
        assertThat(updated.path("status").asString()).isEqualTo("RESOLVED");
        assertThat(updated.path("firstSeenAt")).isEqualTo(raised.path("firstSeenAt"));
    }

    /**
     * Lease vencida: o líder caiu no HTTP e o prazo passou; a liderança conclui
     * {@code FAILED} sem nova chamada.
     */
    @Test
    void interruptedLeaderPastItsDeadlineIsConcludedWithoutACallAndAnswersItsFollowers() {
        String vehicle = "vehicle-lease-" + UUID.randomUUID().toString().substring(0, 8);
        TestRequests.Request leader = TestRequests.enrichment(vehicle, deadline(), ENRICHMENT_PROVIDER);
        TestRequests.Request follower = TestRequests.enrichment(vehicle, deadline(), ENRICHMENT_PROVIDER);
        String queryKey = queryKey(vehicle, "driver-ana");
        givenInterruptedLeader(queryKey, leader);
        givenFollower(queryKey, 1, follower);

        assertThat(queries.reconcile(leader.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.UNKNOWN);

        assertThat(PROVIDERS.calls(leader.queryId())).isZero();
        assertThat(attribute(item("QUERY#" + leader.queryId(), "META"), "state")).contains("UNKNOWN");
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "state")).contains("COMPLETED");
        assertThat(attribute(inflight, "resultType")).contains("FAILED");
        assertThat(enrichmentResult(leader.queryId(), leader).path("errorClass").asString())
                .isEqualTo("INTERRUPTED_ATTEMPT");
        JsonNode joined = enrichmentResult(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("resultType").asString()).isEqualTo("FAILED");
        assertThat(item("QUERY#" + leader.queryId(), "FANOUT")).doesNotContainKey("gsi2pk");
        assertThat(attribute(item("ISSUE#" + Identities.issueId(IssueType.UNKNOWN_QUERY, leader.queryId()), "META"),
                "status")).contains("OPEN");
    }

    /**
     * Pendência do M2: o líder conclui {@code FAILED} e cai antes do fan-out; outra
     * cadeia começa antes da reentrega
     * dele, que por isso não alcança os seguidores. O reconciler os responde pela
     * cadeia gravada na conclusão.
     */
    @Test
    void m2_failedLeaderThatFellBeforeTheFanOutStillAnswersTheFollowersOfItsChain() {
        String driver = "driver-" + UUID.randomUUID().toString().substring(0, 8);
        String vehicle = ProviderStub.VEHICLE_ENRICHMENT_DOWN;
        String queryKey = queryKey(vehicle, driver);
        TestRequests.Request leader = TestRequests.enrichment(vehicle, driver, deadline(), ENRICHMENT_PROVIDER);
        enrichmentWorker.apply(sqsEvent(leader.body()));
        Map<String, AttributeValue> fanOut = item("QUERY#" + leader.queryId(), "FANOUT");
        assertThat(attribute(fanOut, "fannedOutAt")).as("the leader finished its own fan-out").isPresent();
        assertThat(fanOut).doesNotContainKey("gsi2pk");
        TestRequests.Request follower = TestRequests.enrichment(vehicle, driver, deadline(), ENRICHMENT_PROVIDER);
        givenFollower(queryKey, 1, follower);
        givenFanOutPending(leader.queryId(), attribute(fanOut, "concludedAt").orElseThrow());
        TestRequests.Request next = TestRequests.enrichment(vehicle, driver, deadline(), ENRICHMENT_PROVIDER);
        enrichmentWorker.apply(sqsEvent(next.body()));
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "firstFencingVersion")).as("another chain").contains("2");
        assertThat(attribute(inflight, "queryId")).contains(next.queryId());
        enrichmentWorker.apply(sqsEvent(leader.body()));
        String recovered = EventIds.enrichmentResolved(leader.queryId(), follower.workRef());
        assertThat(item("OUTBOX#" + recovered, "META")).as("the leader redelivery cannot reach the old chain")
                .isEmpty();
        int leaderCalls = PROVIDERS.calls(leader.queryId());
        int nextCalls = PROVIDERS.calls(next.queryId());
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);

        assertThat(fanOuts.run(500, TestClock.INSTANCE.instant())).isPositive();

        JsonNode joined = enrichmentResult(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("resultType").asString()).isEqualTo("FAILED");
        assertThat(joined.path("errorClass").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(item("CONSUMED#" + follower.eventId(), "META")).isNotEmpty();
        assertThat(item("QUERY#" + leader.queryId(), "FANOUT")).doesNotContainKey("gsi2pk");
        assertThat(PROVIDERS.calls(leader.queryId())).isEqualTo(leaderCalls);
        assertThat(PROVIDERS.calls(next.queryId())).isEqualTo(nextCalls);
        Map<String, AttributeValue> answered = item("OUTBOX#" + recovered, "META");
        fanOuts.run(500, TestClock.INSTANCE.instant());
        assertThat(item("OUTBOX#" + recovered, "META")).isEqualTo(answered);
    }

    @Test
    void stuckReceivedCallbackIsProcessedByTheReconcilerWithTheWorkerCode() {
        TestRequests.Request request = acceptedBetaQuery("vehicle-stuck-callback");
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        String externalEventId = givenInbox(providerRequestId, request.queryId(), Duration.ofSeconds(40));

        InboxReconciliation.Report report = inbox.run(500, TestClock.INSTANCE.instant());

        assertThat(report.processed()).isPositive();
        Map<String, AttributeValue> processed = item("INBOX#beta#" + externalEventId, "META");
        assertThat(attribute(processed, "processingStatus")).contains("PROCESSED");
        assertThat(processed).doesNotContainKeys("gsi2pk", "gsi2sk");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(insurerResult(request.queryId()).path("status").asString()).isEqualTo("QUOTED");
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
    }

    @Test
    void callbackWithoutCorrelationBecomesUnmatchedWithAnIssueAndIsProcessedWhenTheCorrelationAppears() {
        TestRequests.Request request = TestRequests.insurer("vehicle-orphan-callback", deadline(), BETA);
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        String externalEventId = givenInbox(providerRequestId, null, Duration.ofSeconds(70));
        String target = "beta#" + externalEventId;

        inbox.run(500, TestClock.INSTANCE.instant());

        Map<String, AttributeValue> unmatched = item("INBOX#beta#" + externalEventId, "META");
        assertThat(attribute(unmatched, "processingStatus")).contains("UNMATCHED");
        assertThat(attribute(unmatched, "gsi2pk")).contains("INBOX_UNMATCHED");
        String issueId = Identities.issueId(IssueType.UNMATCHED_CALLBACK, target);
        JsonNode raised = issueEvent(issueId, 1, "OperationIssueRaised");
        assertThat(raised.path("type").asString()).isEqualTo("UNMATCHED_CALLBACK");
        assertThat(raised.has("organizationId")).as("a callback without correlation has no organization").isFalse();
        assertThat(raised.path("reconcilable").asBoolean()).isFalse();
        insurerWorker.apply(sqsEvent(request.body()));
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).as("the worker half alone")
                .contains("IN_FLIGHT");

        inbox.run(500, TestClock.INSTANCE.instant());
        issueReview.run(500, TestClock.INSTANCE.instant());

        assertThat(attribute(item("INBOX#beta#" + externalEventId, "META"), "processingStatus")).contains("PROCESSED");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(attribute(item("ISSUE#" + issueId, "META"), "status")).contains("RESOLVED");
        assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
    }

    @Test
    void outboxPendingBeyondTheThresholdOpensStaleOutboxUntilItIsPublished() {
        String eventId = UUID.randomUUID().toString();
        givenUnpublishableOutbox(eventId, Instant.parse("2020-01-01T00:00:00Z"));

        OutboxReconciliation.Report report = outbox.run(1, TestClock.INSTANCE.instant());

        assertThat(report.stale()).isEqualTo(1);
        assertThat(report.oldestAgeSeconds()).isGreaterThan(Duration.ofDays(365).toSeconds());
        String issueId = Identities.issueId(IssueType.STALE_OUTBOX, eventId);
        assertThat(attribute(item("ISSUE#" + issueId, "META"), "status")).contains("OPEN");
        assertThat(issueEvent(issueId, 1, "OperationIssueRaised").path("type").asString()).isEqualTo("STALE_OUTBOX");
        givenPublished(eventId);

        issueReview.run(500, TestClock.INSTANCE.instant());

        assertThat(attribute(item("ISSUE#" + issueId, "META"), "status")).contains("RESOLVED");
    }

    @Test
    void unknownQueriesWithoutAStatusApiLeaveTheIndexAndTheOthersTakeTurns() {
        TestRequests.Request alpha = TestRequests.insurer(ProviderStub.VEHICLE_SLOW, deadline());
        insurerWorker.apply(sqsEvent(alpha.body()));
        assertThat(attribute(item("QUERY#" + alpha.queryId(), "META"), "state")).contains("UNKNOWN");
        TestRequests.Request first = unknownBetaQuery();
        TestRequests.Request second = unknownBetaQuery();
        indexedAt("QUERY#" + alpha.queryId(), "2000-01-01T00:00:00.000Z");
        indexedAt("QUERY#" + first.queryId(), "2000-01-01T00:00:01.000Z");
        indexedAt("QUERY#" + second.queryId(), "2000-01-01T00:00:02.000Z");
        String secondRequestId = ProviderStub.betaRequestId(second.queryId());
        int secondReads = PROVIDERS.statusReads(secondRequestId);

        for (int cycle = 0; cycle < 3; cycle++) {
            queries.run(1, TestClock.INSTANCE.instant());
        }

        Map<String, AttributeValue> withoutStatusApi = item("QUERY#" + alpha.queryId(), "META");
        assertThat(attribute(withoutStatusApi, "state")).contains("UNKNOWN");
        assertThat(withoutStatusApi).as("nothing concludes it on its own; the open issue follows it")
                .doesNotContainKeys("gsi2pk", "gsi2sk");
        assertThat(attribute(issue(IssueType.UNKNOWN_QUERY, alpha.queryId()), "status")).contains("OPEN");
        assertThat(PROVIDERS.statusReads(secondRequestId)).as("the newer UNKNOWN gets its turn in the batch")
                .isEqualTo(secondReads + 1);
        assertThat(attribute(item("QUERY#" + first.queryId(), "META"), "gsi2pk")).contains("QUERY_UNKNOWN");
    }

    @Test
    void statusReadsOfAnInsurerThatDidNotAnswerStopForTheRestOfTheCycle() {
        TestRequests.Request first = unknownBetaQuery();
        TestRequests.Request second = unknownBetaQuery();
        String firstRequestId = ProviderStub.betaRequestId(first.queryId());
        String secondRequestId = ProviderStub.betaRequestId(second.queryId());
        PROVIDERS.betaStatus(firstRequestId, "{}");
        PROVIDERS.betaStatus(secondRequestId, "{}");
        indexedAt("QUERY#" + first.queryId(), "2000-01-01T00:00:00.000Z");
        indexedAt("QUERY#" + second.queryId(), "2000-01-01T00:00:01.000Z");
        int firstReads = PROVIDERS.statusReads(firstRequestId);
        int secondReads = PROVIDERS.statusReads(secondRequestId);

        queries.run(500, TestClock.INSTANCE.instant());

        assertThat(PROVIDERS.statusReads(firstRequestId)).isEqualTo(firstReads + 1);
        assertThat(PROVIDERS.statusReads(secondRequestId)).as("left for the next cycle").isEqualTo(secondReads);
        assertThat(attribute(item("QUERY#" + second.queryId(), "META"), "state")).contains("UNKNOWN");
    }

    @Test
    void unknownQueryConcludedByTheStatusApiMeetsTheCallbackThatCameOnlyWithTheProviderRequestId() {
        TestRequests.Request request = TestRequests.insurer("vehicle-beta-silent", deadline(), BETA);
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        givenUnknownWithoutTheProviderRequestId(request);
        String externalEventId = "bev_" + UUID.randomUUID();
        assertThat(api.receive(BETA, callback(externalEventId, providerRequestId, null, offer())))
                .as("the callback has neither clientReference nor the worker half").isEqualTo("RECEIVED");
        PROVIDERS.betaStatus(request.queryId(), status(providerRequestId, request.queryId(), "COMPLETED", offer()));

        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.CONCLUDED);

        assertThat(attribute(item("INBOX#beta#" + externalEventId, "META"), "processingStatus"))
                .as("kept as late evidence, not UNMATCHED").contains("PROCESSED");
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
        assertThat(PROVIDERS.calls(request.queryId())).isZero();
    }

    @Test
    void conclusionWithoutCallLosesToAnAttemptReservedAfterTheReconcilerReadTheQuery() {
        TestRequests.Request request = TestRequests.insurer("vehicle-hatch-2019", deadline());
        EnrichmentWorkerIT.givenInFlightQuery(request, TestClock.INSTANCE.instant().minusSeconds(120), true);
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(2)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("2"),
                "startedAt", AttributeValue.fromS(Timestamps.format(TestClock.INSTANCE.instant())))));
        TestRequests.Request untouched = TestRequests.insurer("vehicle-hatch-2019", deadline());
        EnrichmentWorkerIT.givenInFlightQuery(untouched, TestClock.INSTANCE.instant().minusSeconds(120), true);

        boolean concluded = ledger.settleWithoutNewAttempt(new Settlement(request.queryId(), null, QueryState.FAILED,
                null, null, null, null, request.workRef()), 1, TestClock.INSTANCE.instant());

        assertThat(concluded).as("another delivery may be calling the insurer").isFalse();
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(ledger.settleWithoutNewAttempt(new Settlement(untouched.queryId(), null, QueryState.FAILED, null,
                null, null, null, untouched.workRef()), 1, TestClock.INSTANCE.instant())).isTrue();
        assertThat(attribute(item("QUERY#" + untouched.queryId(), "META"), "state")).contains("FAILED");
        givenQuerySettledByAnotherPath(request.queryId());
    }

    @Test
    void openIssuesThatNeverResolveTakeTurnsWithTheOthersInTheReview() {
        TestRequests.Request alpha = TestRequests.insurer(ProviderStub.VEHICLE_SLOW, deadline());
        insurerWorker.apply(sqsEvent(alpha.body()));
        queries.reconcile(alpha.queryId());
        String eventId = UUID.randomUUID().toString();
        givenUnpublishableOutbox(eventId, Instant.parse("2020-01-01T00:00:00Z"));
        outbox.run(1, TestClock.INSTANCE.instant());
        givenPublished(eventId);
        String permanent = Identities.issueId(IssueType.UNKNOWN_QUERY, alpha.queryId());
        String resolvable = Identities.issueId(IssueType.STALE_OUTBOX, eventId);
        indexedAt("ISSUE#" + permanent, "2000-01-01T00:00:00.000Z");
        indexedAt("ISSUE#" + resolvable, "2000-01-01T00:00:01.000Z");

        for (int cycle = 0; cycle < 3; cycle++) {
            issueReview.run(1, TestClock.INSTANCE.instant());
        }

        assertThat(attribute(item("ISSUE#" + resolvable, "META"), "status")).contains("RESOLVED");
        assertThat(attribute(item("ISSUE#" + permanent, "META"), "status")).contains("OPEN");
    }

    @Test
    void unmatchedCallbacksThatNeverMatchTakeTurnsWithTheOthers() {
        String orphan = givenInbox("brq_" + UUID.randomUUID(), null, Duration.ofSeconds(70));
        TestRequests.Request request = TestRequests.insurer("vehicle-orphan-callback", deadline(), BETA);
        String late = givenInbox(ProviderStub.betaRequestId(request.queryId()), null, Duration.ofSeconds(70));
        inbox.run(500, TestClock.INSTANCE.instant());
        assertThat(attribute(item("INBOX#beta#" + late, "META"), "processingStatus")).contains("UNMATCHED");
        insurerWorker.apply(sqsEvent(request.body()));
        indexedAt("INBOX#beta#" + orphan, "2000-01-01T00:00:00.000Z");
        indexedAt("INBOX#beta#" + late, "2000-01-01T00:00:01.000Z");

        for (int cycle = 0; cycle < 3; cycle++) {
            inbox.run(1, TestClock.INSTANCE.instant());
        }

        assertThat(attribute(item("INBOX#beta#" + late, "META"), "processingStatus")).contains("PROCESSED");
        assertThat(attribute(item("INBOX#beta#" + orphan, "META"), "processingStatus")).contains("UNMATCHED");
    }

    @Test
    void pendingIssueEventDoesNotOpenAnotherIssueInCascade() {
        String eventId = UUID.randomUUID().toString();
        givenPendingIssueEvent(eventId, Instant.parse("2020-01-01T00:00:00Z"));
        try {
            outbox.run(500, TestClock.INSTANCE.instant());

            assertThat(issue(IssueType.STALE_OUTBOX, eventId)).isEmpty();
        } finally {
            givenPublished(eventId);
        }
    }

    @Test
    void reconcilerCycleRunsEveryDutyAndPublishesItsMetricsInEmf() {
        METRIC_LINES.clear();
        TestRequests.Request request = TestRequests.insurer("vehicle-cycle", deadline());
        insurerWorker.apply(sqsEvent(request.body()));

        ReconcileResult result = reconciler.apply(new ReconcileCommand(500));

        assertThat(result.failedDuties()).as("every duty ran to the end").isEmpty();
        JsonNode gauges = metric(Metrics.UNKNOWN_QUERIES);
        assertThat(gauges.path("Stage").asString()).isEqualTo("local");
        assertThat(gauges.path(Metrics.UNMATCHED_CALLBACKS).isNumber()).isTrue();
        assertThat(gauges.path(Metrics.OUTBOX_PENDING_AGE_SECONDS).isNumber()).isTrue();
        JsonNode directive = gauges.path("_aws").path("CloudWatchMetrics").get(0);
        assertThat(directive.path("Namespace").asString()).isEqualTo("mmda/provider-solution");
        assertThat(directive.path("Dimensions").get(0).get(0).asString()).isEqualTo("Stage");
        JsonNode call = METRIC_LINES.stream()
                .map(objectMapper::readTree)
                .filter(line -> "alpha".equals(line.path("Provider").asString()))
                .findFirst()
                .orElseThrow();
        assertThat(call.path(Metrics.PROVIDER_CALLS).asInt()).isEqualTo(1);
        assertThat(call.path(Metrics.PROVIDER_FAILURES).asInt()).isZero();
    }

    private TestRequests.Request acceptedBetaQuery(String vehicle) {
        TestRequests.Request request = TestRequests.insurer(vehicle, deadline(), BETA);
        insurerWorker.apply(sqsEvent(request.body()));
        Map<String, AttributeValue> query = item("QUERY#" + request.queryId(), "META");
        assertThat(attribute(query, "state")).contains("IN_FLIGHT");
        assertThat(attribute(query, "providerRequestId")).contains(ProviderStub.betaRequestId(request.queryId()));
        return request;
    }

    private static Instant deadline() {
        return TestClock.INSTANCE.instant().plus(DEADLINE);
    }

    private String queryKey(String vehicle, String driver) {
        return QueryKeys.compute(queryKeySecret.value(), "org-acme", ENRICHMENT_PROVIDER, "VEHICLE_DRIVER_PROFILE",
                "1", vehicle, driver);
    }

    private JsonNode insurerResult(String queryId) {
        return outboxDetail(EventIds.insurerResultRecorded(queryId), "InsurerResultRecorded");
    }

    private JsonNode enrichmentResult(String queryId, TestRequests.Request recipient) {
        JsonNode detail = outboxDetail(EventIds.enrichmentResolved(queryId, recipient.workRef()), "EnrichmentResolved");
        assertThat(detail.path("workRef").asString()).isEqualTo(recipient.workRef());
        return detail;
    }

    private JsonNode issueEvent(String issueId, long revision, String detailType) {
        JsonNode detail = outboxDetail(EventIds.operationIssue(issueId, revision), detailType);
        assertThat(detail.path("issueId").asString()).isEqualTo(issueId);
        assertThat(detail.path("revision").asLong()).isEqualTo(revision);
        return detail;
    }

    private JsonNode outboxDetail(String eventId, String detailType) {
        Map<String, AttributeValue> outboxItem = item("OUTBOX#" + eventId, "META");
        assertThat(attribute(outboxItem, "detailType")).as("outbox %s", eventId).contains(detailType);
        return objectMapper.readTree(attribute(outboxItem, "detail").orElseThrow());
    }

    private JsonNode metric(String name) {
        return METRIC_LINES.stream()
                .map(objectMapper::readTree)
                .filter(line -> line.has(name))
                .reduce((first, last) -> last)
                .orElseThrow(() -> new AssertionError("no EMF line with " + name));
    }

    private static Map<String, AttributeValue> issue(IssueType type, String target) {
        return item("ISSUE#" + Identities.issueId(type, target), "META");
    }

    /**
     * Situação da beta como o {@code GET /sim/insurers/beta/quotes/{ref}} do
     * simulador a devolve.
     */
    private static String status(String providerRequestId, String clientReference, String status, String result) {
        String completed = result == null ? ""
                : ",\"completedAt\":\"%s\",\"result\":%s"
                        .formatted(Timestamps.format(Instant.now()), result);
        return """
                {"providerRequestId":"%s","clientReference":"%s","status":"%s","receivedAt":"%s"%s}"""
                .formatted(providerRequestId, clientReference, status, Timestamps.format(Instant.now()), completed);
    }

    private static String offer() {
        return """
                {"outcome":"OFFER","offerNumber":"BETA-5E6F7A8B","insurerName":"Beta Seguros",\
                "premium":{"total":"2510.00","currency":"BRL"},\
                "paymentOptions":[{"installments":1,"installmentAmount":"2510.00"}],\
                "coverages":[{"code":"COLLISION","description":"Colisão, incêndio e roubo","limit":"75000.00"}],\
                "deductibles":[{"coverage":"COLLISION","amount":"3750.00"}],\
                "expiresAt":"2026-12-01T00:00:00.000Z"}""";
    }

    private static String callback(String externalEventId, String providerRequestId, String clientReference,
            String result) {
        String reference = clientReference == null ? "" : ",\"clientReference\":\"" + clientReference + "\"";
        return """
                {"externalEventId":"%s","providerRequestId":"%s"%s,"sentAt":"%s","result":%s}"""
                .formatted(externalEventId, providerRequestId, reference, Timestamps.format(Instant.now()), result);
    }

    /**
     * Callback gravado pela {@code api}, que caiu antes do encontro, recebido há
     * {@code age}.
     */
    private static String givenInbox(String providerRequestId, String clientReference, Duration age) {
        String externalEventId = "bev_" + UUID.randomUUID();
        String receivedAt = Timestamps.format(TestClock.INSTANCE.instant().minus(age));
        Map<String, AttributeValue> inboxItem = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("INBOX#beta#" + externalEventId),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("INBOX"),
                "provider", AttributeValue.fromS(BETA),
                "externalEventId", AttributeValue.fromS(externalEventId),
                "providerRequestId", AttributeValue.fromS(providerRequestId),
                "payload", AttributeValue.fromS(callback(externalEventId, providerRequestId, clientReference, offer())),
                "receivedAt", AttributeValue.fromS(receivedAt),
                "processingStatus", AttributeValue.fromS("RECEIVED"),
                "gsi2pk", AttributeValue.fromS("INBOX_RECEIVED")));
        inboxItem.put("gsi2sk", AttributeValue.fromS(receivedAt));
        if (clientReference != null) {
            inboxItem.put("clientReference", AttributeValue.fromS(clientReference));
        }
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(inboxItem));
        return externalEventId;
    }

    /**
     * Líder caído no HTTP, com a lease vencida e o prazo do pedido já passado, como
     * o worker o gravou.
     */
    private static void givenInterruptedLeader(String queryKey, TestRequests.Request leader) {
        Instant now = TestClock.INSTANCE.instant();
        String startedAt = Timestamps.format(now.minusSeconds(200));
        String deadlineAt = Timestamps.format(now.minusSeconds(10));
        Map<String, AttributeValue> query = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + leader.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "operation", AttributeValue.fromS("ENRICH"),
                "provider", AttributeValue.fromS(ENRICHMENT_PROVIDER),
                "organizationId", AttributeValue.fromS("org-acme"),
                "workRef", AttributeValue.fromS(leader.workRef()),
                "quoteId", AttributeValue.fromS(leader.quoteId()),
                "version", AttributeValue.fromN("1")));
        query.put("queryKey", AttributeValue.fromS(queryKey));
        query.put("fencingVersion", AttributeValue.fromN("1"));
        query.put("deadlineAt", AttributeValue.fromS(deadlineAt));
        query.put("gsi2pk", AttributeValue.fromS("QUERY_IN_FLIGHT"));
        query.put("gsi2sk", AttributeValue.fromS(deadlineAt));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(query));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + leader.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(startedAt))));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("INFLIGHT#" + queryKey),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("INFLIGHT"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "fencingVersion", AttributeValue.fromN("1"),
                "firstFencingVersion", AttributeValue.fromN("1"),
                "leaderWorkRef", AttributeValue.fromS(leader.workRef()),
                "queryId", AttributeValue.fromS(leader.queryId()),
                "leaseUntil", AttributeValue.fromS(Timestamps.format(now.minusSeconds(170))))));
    }

    /**
     * Consulta da beta com o {@code 202} gravado, como o worker a deixa, mas sem a
     * metade dele no {@code CORR#}.
     */
    private static void givenAcceptedWithoutTheWorkerHalf(TestRequests.Request request, String providerRequestId) {
        String startedAt = Timestamps.format(TestClock.INSTANCE.instant());
        String deadlineAt = Timestamps.format(deadline());
        Map<String, AttributeValue> query = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "operation", AttributeValue.fromS("QUOTE"),
                "provider", AttributeValue.fromS(BETA),
                "insurerId", AttributeValue.fromS(BETA),
                "organizationId", AttributeValue.fromS("org-acme"),
                "workRef", AttributeValue.fromS(request.workRef()),
                "quoteId", AttributeValue.fromS(request.quoteId())));
        query.put("version", AttributeValue.fromN("1"));
        query.put("requestEventId", AttributeValue.fromS(request.eventId()));
        query.put("providerRequestId", AttributeValue.fromS(providerRequestId));
        query.put("deadlineAt", AttributeValue.fromS(deadlineAt));
        query.put("gsi2pk", AttributeValue.fromS("QUERY_IN_FLIGHT"));
        query.put("gsi2sk", AttributeValue.fromS(deadlineAt));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(query));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(startedAt),
                "acceptedAt", AttributeValue.fromS(startedAt),
                "httpStatus", AttributeValue.fromN("202"),
                "providerRequestId", AttributeValue.fromS(providerRequestId))));
    }

    private static void givenFollower(String queryKey, long fencingVersion, TestRequests.Request follower) {
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("INFLIGHT#" + queryKey),
                "sk", AttributeValue.fromS(Items.followerSk(fencingVersion, follower.workRef())),
                "type", AttributeValue.fromS("FOLLOWER"),
                "workRef", AttributeValue.fromS(follower.workRef()),
                "quoteId", AttributeValue.fromS(follower.quoteId()),
                "version", AttributeValue.fromN("1"),
                "fencingVersion", AttributeValue.fromN(Long.toString(fencingVersion)))));
    }

    /**
     * O estado de um líder que caiu entre a conclusão e o fan-out: o item do
     * fan-out continua pendente.
     */
    private static void givenFanOutPending(String queryId, String concludedAt) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS("QUERY#" + queryId), "sk", AttributeValue.fromS("FANOUT")))
                .updateExpression("SET gsi2pk = :pending, gsi2sk = :concludedAt REMOVE fannedOutAt")
                .expressionAttributeValues(Map.of(
                        ":pending", AttributeValue.fromS("FANOUT_PENDING"),
                        ":concludedAt", AttributeValue.fromS(concludedAt))));
    }

    private static void givenQuerySettledByAnotherPath(String queryId) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS("QUERY#" + queryId), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET #state = :completed REMOVE gsi2pk, gsi2sk")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(":completed", AttributeValue.fromS("COMPLETED"))));
    }

    /**
     * Consulta da beta que ficou {@code UNKNOWN} com a API de situação ainda em
     * {@code PROCESSING}.
     */
    private TestRequests.Request unknownBetaQuery() {
        TestRequests.Request request = acceptedBetaQuery("vehicle-beta-late");
        String providerRequestId = ProviderStub.betaRequestId(request.queryId());
        PROVIDERS.betaStatus(providerRequestId, status(providerRequestId, request.queryId(), "PROCESSING", null));
        TestClock.INSTANCE.advance(PAST_EVERY_LIVE_DELIVERY);
        assertThat(queries.reconcile(request.queryId()).outcome()).isEqualTo(QueryReconciliation.Outcome.UNKNOWN);
        return request;
    }

    /**
     * Põe o item na posição dada do seu índice {@code gsi2}, para o teste controlar
     * a ordem do lote.
     */
    private static void indexedAt(String pk, String gsi2sk) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET gsi2sk = :at")
                .expressionAttributeValues(Map.of(":at", AttributeValue.fromS(gsi2sk))));
    }

    /**
     * O {@code 202} da beta se perdeu: a tentativa ficou aberta, sem
     * {@code providerRequestId}, e a consulta UNKNOWN.
     */
    private static void givenUnknownWithoutTheProviderRequestId(TestRequests.Request request) {
        String startedAt = Timestamps.format(TestClock.INSTANCE.instant().minusSeconds(120));
        Map<String, AttributeValue> query = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("UNKNOWN"),
                "operation", AttributeValue.fromS("QUOTE"),
                "provider", AttributeValue.fromS(BETA),
                "insurerId", AttributeValue.fromS(BETA),
                "organizationId", AttributeValue.fromS("org-acme"),
                "workRef", AttributeValue.fromS(request.workRef()),
                "quoteId", AttributeValue.fromS(request.quoteId())));
        query.put("version", AttributeValue.fromN("1"));
        query.put("requestEventId", AttributeValue.fromS(request.eventId()));
        query.put("deadlineAt", AttributeValue.fromS(Timestamps.format(TestClock.INSTANCE.instant().minusSeconds(60))));
        query.put("gsi2pk", AttributeValue.fromS("QUERY_UNKNOWN"));
        query.put("gsi2sk", AttributeValue.fromS(startedAt));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(query));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + request.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(startedAt))));
    }

    /**
     * Evento de ocorrência pendente, ainda fora da hora de republicar, como no
     * barramento fora do ar.
     */
    private static void givenPendingIssueEvent(String eventId, Instant createdAt) {
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("OUTBOX#" + eventId),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("OUTBOX"),
                "eventId", AttributeValue.fromS(eventId),
                "detailType", AttributeValue.fromS("OperationIssueRaised"),
                "detail", AttributeValue.fromS("{\"eventId\":\"" + eventId + "\"}"),
                "createdAt", AttributeValue.fromS(Timestamps.format(createdAt)),
                "attempts", AttributeValue.fromN("1"),
                "gsi2pk", AttributeValue.fromS("OUTBOX_PENDING"),
                "gsi2sk", AttributeValue.fromS("2999-01-01T00:00:00.000Z"))));
    }

    /** Outbox sem {@code detail}, que nenhuma publicação consegue enviar. */
    private static void givenUnpublishableOutbox(String eventId, Instant createdAt) {
        String created = Timestamps.format(createdAt);
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("OUTBOX#" + eventId),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("OUTBOX"),
                "eventId", AttributeValue.fromS(eventId),
                "detailType", AttributeValue.fromS("InsurerResultRecorded"),
                "createdAt", AttributeValue.fromS(created),
                "attempts", AttributeValue.fromN("0"),
                "gsi2pk", AttributeValue.fromS("OUTBOX_PENDING"),
                "gsi2sk", AttributeValue.fromS(created))));
    }

    private static void givenPublished(String eventId) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS("OUTBOX#" + eventId), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET publishedAt = :now REMOVE gsi2pk, gsi2sk")
                .expressionAttributeValues(Map.of(":now", AttributeValue.fromS(Timestamps.format(Instant.now())))));
    }
}

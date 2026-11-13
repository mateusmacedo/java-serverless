package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryKeys;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reuso e liderança do {@code enrichmentWorker} no Floci (desenho, §8; PRD, T04, T05, T06 e T15). Cada caso usa
 * um veículo próprio, então começa sem {@code REUSE#} nem {@code INFLIGHT#} da sua {@code queryKey}. O
 * {@link TestClock} vence a validade da evidência e a lease da liderança sem espera real, e o espião no
 * {@link ReuseLedger} provoca as corridas entre a leitura e a escrita.
 */
class EnrichmentReuseIT extends FlociIntegrationTest {

    private static final Duration DEADLINE = Duration.ofMinutes(4);
    private static final int EQUIVALENT_REQUESTS = 100;

    @TestBean
    private Clock clock;

    @MockitoSpyBean
    private ReuseLedger reuseLedger;

    @MockitoSpyBean
    private QueryLedger queryLedger;

    @Autowired
    private EnrichmentWorkerFunction worker;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private QueryKeySecret queryKeySecret;

    @Autowired
    private ProviderProperties properties;

    @Autowired
    private ReuseProperties reuse;

    static Clock clock() {
        return TestClock.INSTANCE;
    }

    /**
     * WHY: os líderes do T06 e do T15 esperam o teste soltar a resposta, além do 1 s de timeout HTTP da base; o
     * timeout fica acima do teto da retenção do {@link ProviderStub}.
     */
    @DynamicPropertySource
    static void heldProviderProperties(DynamicPropertyRegistry registry) {
        registry.add("provider.http-timeout-seconds",
                () -> Long.toString(ProviderStub.MAX_HOLD.plusSeconds(10).toSeconds()));
    }

    @AfterEach
    void resetClock() {
        TestClock.INSTANCE.reset();
    }

    @Test
    void t04_reuseHitCompletesWithoutExternalCall() {
        String vehicle = vehicle("t04");
        TestRequests.Request first = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(first.body()));
        JsonNode miss = resolved(first.queryId(), first);
        String evidenceSk = "EVIDENCE#" + miss.path("evidenceId").asString();
        Map<String, AttributeValue> evidenceBefore = item("QUERY#" + first.queryId(), evidenceSk);
        TestRequests.Request second = request(vehicle);

        SQSBatchResponse response = worker.apply(EnrichmentWorkerIT.sqsEvent(second.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
        assertThat(PROVIDERS.calls(second.queryId())).isZero();
        assertThat(item("QUERY#" + second.queryId(), "META")).isEmpty();
        JsonNode hit = resolved(first.queryId(), second);
        assertThat(hit.path("reuseDecision").asString()).isEqualTo("HIT");
        assertThat(hit.path("quoteId").asString()).isEqualTo(second.quoteId());
        assertThat(hit.path("queryId").asString()).isEqualTo(first.queryId());
        assertThat(hit.path("evidenceId")).isEqualTo(miss.path("evidenceId"));
        assertThat(hit.path("result")).isEqualTo(miss.path("result"));
        assertThat(hit.path("reusableUntil")).isEqualTo(miss.path("reusableUntil"));
        assertThat(hit.path("policyVersion").asString()).isEqualTo(reuse.policyVersion());
        assertThat(item("QUERY#" + first.queryId(), evidenceSk)).isEqualTo(evidenceBefore);
        Map<String, AttributeValue> index = item("REUSE#" + queryKey(vehicle), "META");
        assertThat(attribute(index, "evidenceId")).contains(miss.path("evidenceId").asString());
        assertThat(attribute(index, "revision")).contains("1");
        assertThat(attribute(index, "policyVersion")).contains(reuse.policyVersion());
        assertThat(attribute(evidenceBefore, "policyVersion")).contains(reuse.policyVersion());
        assertThat(item("CONSUMED#" + second.eventId(), "META")).isNotEmpty();
    }

    @Test
    void t05_expiredReuseStartsNewQueryAndKeepsHistory() {
        String vehicle = vehicle("t05");
        TestRequests.Request first = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(first.body()));
        String firstEvidenceId = resolved(first.queryId(), first).path("evidenceId").asString();
        List<Map<String, AttributeValue>> history = partition(first.queryId());
        Map<String, AttributeValue> firstOutbox = item(
                "OUTBOX#" + EventIds.enrichmentResolved(first.queryId(), first.workRef()), "META");
        TestClock.INSTANCE.advance(Duration.ofSeconds(properties.enrichmentValiditySeconds() + 1L));
        TestRequests.Request second = request(vehicle);

        worker.apply(EnrichmentWorkerIT.sqsEvent(second.body()));

        assertThat(PROVIDERS.calls(second.queryId())).isEqualTo(1);
        JsonNode renewed = resolved(second.queryId(), second);
        assertThat(renewed.path("reuseDecision").asString()).isEqualTo("MISS");
        assertThat(renewed.path("evidenceId").asString()).isNotEqualTo(firstEvidenceId);
        assertThat(attribute(item("QUERY#" + second.queryId(), "META"), "fencingVersion")).contains("2");
        Map<String, AttributeValue> index = item("REUSE#" + queryKey(vehicle), "META");
        assertThat(attribute(index, "queryId")).contains(second.queryId());
        assertThat(attribute(index, "evidenceId")).contains(renewed.path("evidenceId").asString());
        assertThat(attribute(index, "revision")).contains("2");
        assertThat(partition(first.queryId())).isEqualTo(history);
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(first.queryId(), first.workRef()), "META"))
                .isEqualTo(firstOutbox);
    }

    @Test
    void t06_hundredEquivalentConcurrentRequestsElectOneLeader() throws Exception {
        String vehicle = vehicle("t06");
        List<TestRequests.Request> requests = IntStream.range(0, EQUIVALENT_REQUESTS)
                .mapToObj(index -> request(vehicle))
                .toList();
        CountDownLatch slowProvider = PROVIDERS.hold(vehicle);
        ExecutorService pool = Executors.newFixedThreadPool(EQUIVALENT_REQUESTS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<SQSBatchResponse>> deliveries = requests.stream()
                    .map(request -> pool.submit(() -> {
                        start.await();
                        return worker.apply(EnrichmentWorkerIT.sqsEvent(request.body()));
                    }))
                    .toList();
            start.countDown();
            await().atMost(ProviderStub.MAX_HOLD.minusSeconds(10))
                    .until(() -> deliveries.stream().filter(Future::isDone).count() >= EQUIVALENT_REQUESTS - 1);
            slowProvider.countDown();
            for (Future<SQSBatchResponse> delivery : deliveries) {
                assertThat(delivery.get(30, TimeUnit.SECONDS).getBatchItemFailures()).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(PROVIDERS.expiredHolds()).as("the test released the leader").doesNotContain(vehicle);

        List<TestRequests.Request> leaders = requests.stream()
                .filter(request -> !item("QUERY#" + request.queryId(), "META").isEmpty())
                .toList();
        assertThat(leaders).hasSize(1);
        TestRequests.Request leader = leaders.getFirst();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
        String queryKey = queryKey(vehicle);
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "state")).contains("COMPLETED");
        assertThat(attribute(inflight, "fencingVersion")).contains("1");
        assertThat(attribute(inflight, "leaderWorkRef")).contains(leader.workRef());
        assertThat(attribute(inflight, "ttl")).isPresent();
        assertThat(followers(queryKey)).hasSize(EQUIVALENT_REQUESTS - 1);
        String evidenceId = attribute(inflight, "evidenceId").orElseThrow();
        for (TestRequests.Request request : requests) {
            JsonNode detail = resolved(leader.queryId(), request);
            assertThat(detail.path("workRef").asString()).isEqualTo(request.workRef());
            assertThat(detail.path("queryId").asString()).isEqualTo(leader.queryId());
            assertThat(detail.path("evidenceId").asString()).isEqualTo(evidenceId);
            assertThat(detail.path("resultType").asString()).isEqualTo("FOUND");
            assertThat(detail.path("reuseDecision").asString()).isEqualTo(request == leader ? "MISS" : "JOIN");
            assertThat(item("CONSUMED#" + request.eventId(), "META")).isNotEmpty();
        }
    }

    @Test
    void t15_staleLeaderCannotOverwriteAfterLeaseTransfer() throws Exception {
        String vehicle = vehicle("t15");
        String queryKey = queryKey(vehicle);
        TestRequests.Request stale = request(vehicle);
        TestRequests.Request follower = request(vehicle);
        CountDownLatch staleResponse = PROVIDERS.hold(stale.queryId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TestRequests.Request successor;
        try {
            Future<SQSBatchResponse> staleDelivery = pool.submit(
                    () -> worker.apply(EnrichmentWorkerIT.sqsEvent(stale.body())));
            await().atMost(Duration.ofSeconds(20)).until(() -> PROVIDERS.calls(stale.queryId()) == 1);
            worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));
            assertThat(item("INFLIGHT#" + queryKey, Items.followerSk(1, follower.workRef()))).isNotEmpty();
            assertThat(staleDelivery.isDone()).as("the stale leader is still waiting for the provider").isFalse();

            TestClock.INSTANCE.advance(reuse.lease().plusSeconds(1));
            successor = request(vehicle);
            worker.apply(EnrichmentWorkerIT.sqsEvent(successor.body()));
            assertThat(staleDelivery.isDone()).as("the successor led while the stale leader waited").isFalse();
            staleResponse.countDown();
            assertThat(staleDelivery.get(30, TimeUnit.SECONDS).getBatchItemFailures()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
        assertThat(PROVIDERS.expiredHolds()).as("the test released the stale leader").doesNotContain(stale.queryId());

        JsonNode current = resolved(successor.queryId(), successor);
        String currentEvidenceId = current.path("evidenceId").asString();
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "state")).contains("COMPLETED");
        assertThat(attribute(inflight, "fencingVersion")).contains("2");
        assertThat(attribute(inflight, "firstFencingVersion")).contains("1");
        assertThat(attribute(inflight, "leaderWorkRef")).contains(successor.workRef());
        assertThat(attribute(inflight, "evidenceId")).contains(currentEvidenceId);
        Map<String, AttributeValue> index = item("REUSE#" + queryKey, "META");
        assertThat(attribute(index, "evidenceId")).contains(currentEvidenceId);
        assertThat(attribute(index, "revision")).contains("1");

        JsonNode staleOwn = resolved(stale.queryId(), stale);
        assertThat(staleOwn.path("reuseDecision").asString()).isEqualTo("MISS");
        String staleEvidenceId = staleOwn.path("evidenceId").asString();
        assertThat(staleEvidenceId).isNotEqualTo(currentEvidenceId);
        Map<String, AttributeValue> staleEvidence = item("QUERY#" + stale.queryId(), "EVIDENCE#" + staleEvidenceId);
        assertThat(attribute(staleEvidence, "resultType")).contains("FOUND");
        assertThat(attribute(staleEvidence, "fencingVersion")).contains("1");
        assertThat(attribute(staleEvidence, "rawResponseRef")).isPresent();
        assertThat(attribute(item("QUERY#" + stale.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(attribute(item("QUERY#" + successor.queryId(), Items.evidenceSk(currentEvidenceId)),
                "fencingVersion")).contains("2");

        JsonNode joined = resolved(successor.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("evidenceId").asString()).isEqualTo(currentEvidenceId);
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(stale.queryId(), follower.workRef()), "META"))
                .isEmpty();
        assertThat(PROVIDERS.calls(follower.queryId())).isZero();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(2);
    }

    @Test
    void declaredNotFoundIsReusedWithTheDeclaredReferences() {
        String driver = driver();
        TestRequests.Request first = request(ProviderStub.VEHICLE_UNKNOWN, driver);
        worker.apply(EnrichmentWorkerIT.sqsEvent(first.body()));
        TestRequests.Request second = request(ProviderStub.VEHICLE_UNKNOWN, driver);

        worker.apply(EnrichmentWorkerIT.sqsEvent(second.body()));

        assertThat(PROVIDERS.calls(first.queryId())).isEqualTo(1);
        assertThat(PROVIDERS.calls(second.queryId())).isZero();
        JsonNode hit = resolved(first.queryId(), second);
        assertThat(hit.path("reuseDecision").asString()).isEqualTo("HIT");
        assertThat(hit.path("resultType").asString()).isEqualTo("NOT_FOUND");
        assertThat(hit.path("notFoundRefs").get(0).asString()).isEqualTo("vehicleRef");
        assertThat(hit.path("evidenceId")).isEqualTo(resolved(first.queryId(), first).path("evidenceId"));
    }

    @Test
    void failureIsNeverReusedAndTheNextRequestLeadsANewChain() {
        String driver = driver();
        TestRequests.Request first = request(ProviderStub.VEHICLE_ENRICHMENT_DOWN, driver);
        worker.apply(EnrichmentWorkerIT.sqsEvent(first.body()));
        String queryKey = queryKey(ProviderStub.VEHICLE_ENRICHMENT_DOWN, driver);
        assertThat(item("REUSE#" + queryKey, "META")).isEmpty();
        assertThat(attribute(item("INFLIGHT#" + queryKey, "META"), "resultType")).contains("FAILED");
        TestRequests.Request second = request(ProviderStub.VEHICLE_ENRICHMENT_DOWN, driver);

        worker.apply(EnrichmentWorkerIT.sqsEvent(second.body()));

        assertThat(PROVIDERS.calls(second.queryId())).isEqualTo(3);
        JsonNode retried = resolved(second.queryId(), second);
        assertThat(retried.path("reuseDecision").asString()).isEqualTo("MISS");
        assertThat(retried.path("errorClass").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "fencingVersion")).contains("2");
        assertThat(attribute(inflight, "firstFencingVersion")).contains("2");
        assertThat(item("REUSE#" + queryKey, "META")).isEmpty();
    }

    @Test
    void redeliveriesOfTheLeaderAndOfAFollowerNeverDuplicateTheResolution() throws Exception {
        String vehicle = vehicle("redelivery");
        TestRequests.Request leader = request(vehicle);
        TestRequests.Request follower = request(vehicle);
        String joinedEventId = EventIds.enrichmentResolved(leader.queryId(), follower.workRef());
        CountDownLatch leaderResponse = PROVIDERS.hold(leader.queryId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SQSBatchResponse> leaderDelivery = pool.submit(
                    () -> worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body())));
            await().atMost(Duration.ofSeconds(20)).until(() -> PROVIDERS.calls(leader.queryId()) == 1);
            worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

            SQSBatchResponse whileInFlight = worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

            assertThat(whileInFlight.getBatchItemFailures()).isEmpty();
            assertThat(item("OUTBOX#" + joinedEventId, "META")).isEmpty();
            leaderResponse.countDown();
            assertThat(leaderDelivery.get(30, TimeUnit.SECONDS).getBatchItemFailures()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
        assertThat(PROVIDERS.expiredHolds()).doesNotContain(leader.queryId());
        Map<String, AttributeValue> joined = item("OUTBOX#" + joinedEventId, "META");
        assertThat(joined).isNotEmpty();

        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
        assertThat(item("OUTBOX#" + joinedEventId, "META")).isEqualTo(joined);
        assertThat(item("QUERY#" + follower.queryId(), "META")).isEmpty();
        assertThat(followers(queryKey(vehicle))).hasSize(1);
    }

    @Test
    void registeredFollowerThatFindsTheLeadershipCompletedPublishesTheRecordedResult() {
        String vehicle = vehicle("recorded");
        TestRequests.Request leader = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        TestRequests.Request follower = request(vehicle);
        givenFollower(queryKey(vehicle), 1, follower);

        worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

        JsonNode joined = resolved(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("evidenceId")).isEqualTo(resolved(leader.queryId(), leader).path("evidenceId"));
        assertThat(item("CONSUMED#" + follower.eventId(), "META")).isNotEmpty();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    @Test
    void interruptedLeaderConcludesItsLeadershipWithFailureForItsFollowers() {
        String vehicle = vehicle("interrupted");
        String queryKey = queryKey(vehicle);
        TestRequests.Request leader = request(vehicle);
        TestRequests.Request follower = request(vehicle);
        givenInterruptedLeader(queryKey, leader);
        givenFollower(queryKey, 1, follower);

        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));

        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isZero();
        assertThat(attribute(item("QUERY#" + leader.queryId(), "META"), "state")).contains("UNKNOWN");
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "state")).contains("COMPLETED");
        assertThat(attribute(inflight, "resultType")).contains("FAILED");
        assertThat(item("REUSE#" + queryKey, "META")).isEmpty();
        assertThat(resolved(leader.queryId(), leader).path("errorClass").asString()).isEqualTo("INTERRUPTED_ATTEMPT");
        JsonNode joined = resolved(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("resultType").asString()).isEqualTo("FAILED");
        assertThat(joined.path("errorClass").asString()).isEqualTo("INTERRUPTED_ATTEMPT");
    }

    @Test
    void observedAtAheadOfTheReceptionDoesNotExtendTheValidity() {
        String driver = driver();
        TestRequests.Request first = request(ProviderStub.VEHICLE_OBSERVED_AHEAD, driver);
        worker.apply(EnrichmentWorkerIT.sqsEvent(first.body()));
        JsonNode miss = resolved(first.queryId(), first);
        Map<String, AttributeValue> evidence = item("QUERY#" + first.queryId(),
                "EVIDENCE#" + miss.path("evidenceId").asString());
        Instant receivedAt = Timestamps.parse(attribute(evidence, "receivedAt").orElseThrow());
        String validUntil = Timestamps.format(receivedAt.plusSeconds(properties.enrichmentValiditySeconds()));

        assertThat(Timestamps.parse(attribute(evidence, "observedAt").orElseThrow())).as("as declared")
                .isAfter(receivedAt.plus(ProviderStub.OBSERVED_AHEAD).minusSeconds(60));
        assertThat(miss.path("reusableUntil").asString()).isEqualTo(validUntil);
        assertThat(attribute(item("REUSE#" + queryKey(ProviderStub.VEHICLE_OBSERVED_AHEAD, driver), "META"),
                "reusableUntil")).contains(validUntil);
        TestClock.INSTANCE.advance(Duration.ofSeconds(properties.enrichmentValiditySeconds() + 1L));
        TestRequests.Request second = request(ProviderStub.VEHICLE_OBSERVED_AHEAD, driver);
        worker.apply(EnrichmentWorkerIT.sqsEvent(second.body()));
        assertThat(resolved(second.queryId(), second).path("reuseDecision").asString()).isEqualTo("MISS");
        assertThat(PROVIDERS.calls(second.queryId())).isEqualTo(1);
    }

    @Test
    void requestIsDeduplicatedByItsWorkRefNotByTheEventIdItArrivedWith() {
        String vehicle = vehicle("dedup");
        TestRequests.Request leader = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        TestRequests.Request reuser = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(reuser.body()));
        assertThat(resolved(leader.queryId(), reuser).path("reuseDecision").asString()).isEqualTo("HIT");
        forgetReuseIndex(queryKey(vehicle));

        String otherEventId = UUID.randomUUID().toString();
        worker.apply(EnrichmentWorkerIT.sqsEvent(reuser.body().replace(reuser.eventId(), otherEventId)));

        assertThat(item("QUERY#" + reuser.queryId(), "META")).isEmpty();
        assertThat(PROVIDERS.calls(reuser.queryId())).isZero();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    @Test
    void leaderRedeliveryAnswersAFollowerThatTheFanOutNeverReached() {
        String vehicle = vehicle("redo-fan-out");
        TestRequests.Request leader = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        TestRequests.Request follower = request(vehicle);
        givenFollower(queryKey(vehicle), 1, follower);

        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));

        JsonNode joined = resolved(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("evidenceId")).isEqualTo(resolved(leader.queryId(), leader).path("evidenceId"));
        assertThat(item("CONSUMED#" + follower.eventId(), "META")).isNotEmpty();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    @Test
    void interruptedLeaderOfAnOlderChainLeavesTheCurrentLeadershipWithTheSameVersionAlone() {
        String vehicle = vehicle("older-chain");
        String queryKey = queryKey(vehicle);
        TestRequests.Request oldLeader = request(vehicle);
        TestRequests.Request currentLeader = request(vehicle);
        TestRequests.Request follower = request(vehicle);
        givenInterruptedLeader(queryKey, oldLeader);
        givenLeadershipInFlight(queryKey, currentLeader, TestClock.INSTANCE.instant().plus(reuse.lease()));
        givenFollower(queryKey, 1, follower);

        worker.apply(EnrichmentWorkerIT.sqsEvent(oldLeader.body()));

        assertThat(attribute(item("QUERY#" + oldLeader.queryId(), "META"), "state")).contains("UNKNOWN");
        assertThat(resolved(oldLeader.queryId(), oldLeader).path("errorClass").asString())
                .isEqualTo("INTERRUPTED_ATTEMPT");
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "state")).contains("IN_FLIGHT");
        assertThat(attribute(inflight, "queryId")).contains(currentLeader.queryId());
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(oldLeader.queryId(), follower.workRef()), "META"))
                .isEmpty();
    }

    @Test
    void leaderWhoseRequestWasAnsweredMeanwhileStillConcludesForTheIndexAndItsFollowers() {
        String vehicle = vehicle("answered");
        String queryKey = queryKey(vehicle);
        TestRequests.Request leader = request(vehicle);
        TestRequests.Request follower = request(vehicle);
        givenFollower(queryKey, 1, follower);
        doAnswer(invocation -> {
            givenConsumed(leader);
            return invocation.callRealMethod();
        }).doCallRealMethod().when(reuseLedger).reuseEntry(queryKey);

        SQSBatchResponse response = worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(attribute(item("QUERY#" + leader.queryId(), "META"), "state")).contains("COMPLETED");
        assertThat(queryItems(leader.queryId(), "ATTEMPT#")).singleElement()
                .satisfies(attempt -> assertThat(attempt).containsKey("finishedAt"));
        Map<String, AttributeValue> inflight = item("INFLIGHT#" + queryKey, "META");
        assertThat(attribute(inflight, "state")).contains("COMPLETED");
        String evidenceId = attribute(inflight, "evidenceId").orElseThrow();
        assertThat(attribute(item("REUSE#" + queryKey, "META"), "evidenceId")).contains(evidenceId);
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(leader.queryId(), leader.workRef()), "META"))
                .as("the other delivery already answered the request").isEmpty();
        JsonNode joined = resolved(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("evidenceId").asString()).isEqualTo(evidenceId);
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    @Test
    void followerWhoseRegistrationLosesToTheConclusionPublishesTheRecordedResult() {
        String vehicle = vehicle("late-registration");
        String queryKey = queryKey(vehicle);
        TestRequests.Request leader = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        forgetReuseIndex(queryKey);
        setLeadershipState(queryKey, "IN_FLIGHT");
        TestRequests.Request follower = request(vehicle);
        doAnswer(invocation -> {
            setLeadershipState(queryKey, "COMPLETED");
            return invocation.callRealMethod();
        }).when(reuseLedger).register(eq(queryKey), anyLong(), any(), any());

        worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

        JsonNode joined = resolved(leader.queryId(), follower);
        assertThat(joined.path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(joined.path("evidenceId")).isEqualTo(resolved(leader.queryId(), leader).path("evidenceId"));
        assertThat(item("INFLIGHT#" + queryKey, Items.followerSk(1, follower.workRef()))).isEmpty();
        assertThat(item("CONSUMED#" + follower.eventId(), "META")).isNotEmpty();
        assertThat(PROVIDERS.calls(follower.queryId())).isZero();
    }

    @Test
    void followerAlreadyRegisteredByAnotherDeliveryWaitsForTheLeaderWithoutASecondRegistration() {
        String vehicle = vehicle("double-registration");
        String queryKey = queryKey(vehicle);
        TestRequests.Request leader = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        forgetReuseIndex(queryKey);
        setLeadershipState(queryKey, "IN_FLIGHT");
        long fencingVersion = Long.parseLong(
                attribute(item("INFLIGHT#" + queryKey, "META"), "fencingVersion").orElseThrow());
        TestRequests.Request follower = request(vehicle);
        List<ReuseLedger.Registration> registrations = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            givenFollower(queryKey, fencingVersion, follower);
            ReuseLedger.Registration registration = (ReuseLedger.Registration) invocation.callRealMethod();
            registrations.add(registration);
            return registration;
        }).when(reuseLedger).register(eq(queryKey), anyLong(), any(), any());

        SQSBatchResponse response = worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(registrations).containsExactly(ReuseLedger.Registration.ALREADY_REGISTERED);
        assertThat(followers(queryKey)).extracting(item -> attribute(item, "workRef").orElseThrow())
                .containsExactly(follower.workRef());
        String followerResult = "OUTBOX#" + EventIds.enrichmentResolved(leader.queryId(), follower.workRef());
        assertThat(item(followerResult, "META")).as("the leader's fan-out answers the follower").isEmpty();
        assertThat(item("CONSUMED#" + follower.eventId(), "META")).isEmpty();
        assertThat(item("QUERY#" + follower.queryId(), "META")).isEmpty();

        setLeadershipState(queryKey, "COMPLETED");
        worker.apply(EnrichmentWorkerIT.sqsEvent(follower.body()));

        assertThat(resolved(leader.queryId(), follower).path("reuseDecision").asString()).isEqualTo("JOIN");
        assertThat(PROVIDERS.calls(follower.queryId())).isZero();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    @Test
    void deliveryThatLosesItsQueryToAnotherDeliveryOfTheSameRequestNeitherCallsNorPublishes() {
        String vehicle = vehicle("double-lead");
        String queryKey = queryKey(vehicle);
        TestRequests.Request request = request(vehicle);
        doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            givenLiveLeader(queryKey, request);
            return read;
        }).doCallRealMethod().when(reuseLedger).reuseEntry(queryKey);
        List<QueryLedger.Leading> leadings = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            QueryLedger.Leading leading = (QueryLedger.Leading) invocation.callRealMethod();
            leadings.add(leading);
            return leading;
        }).when(queryLedger).lead(any(), any(), any(), any());

        SQSBatchResponse response = worker.apply(EnrichmentWorkerIT.sqsEvent(request.body()));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(leadings).containsExactly(QueryLedger.Leading.QUERY_EXISTS);
        assertThat(attribute(item("QUERY#" + request.queryId(), "META"), "state")).contains("IN_FLIGHT");
        assertThat(queryItems(request.queryId(), "ATTEMPT#")).hasSize(1);
        assertThat(attribute(item("INFLIGHT#" + queryKey, "META"), "leaderWorkRef")).contains(request.workRef());
        assertThat(item("OUTBOX#" + EventIds.enrichmentResolved(request.queryId(), request.workRef()), "META"))
                .isEmpty();
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isZero();
    }

    @Test
    void requestThatLosesTheIndexToAnotherLeaderRereadsItAndUsesTheNewEvidence() {
        String vehicle = vehicle("index-race");
        String queryKey = queryKey(vehicle);
        TestRequests.Request leader = request(vehicle);
        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));
        Map<String, AttributeValue> index = item("REUSE#" + queryKey, "META");
        forgetReuseIndex(queryKey);
        TestRequests.Request request = request(vehicle);
        doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(index));
            return read;
        }).doCallRealMethod().when(reuseLedger).reuseEntry(queryKey);

        worker.apply(EnrichmentWorkerIT.sqsEvent(request.body()));

        JsonNode hit = resolved(leader.queryId(), request);
        assertThat(hit.path("reuseDecision").asString()).isEqualTo("HIT");
        assertThat(hit.path("evidenceId").asString()).isEqualTo(attribute(index, "evidenceId").orElseThrow());
        assertThat(item("QUERY#" + request.queryId(), "META")).isEmpty();
        assertThat(attribute(item("INFLIGHT#" + queryKey, "META"), "fencingVersion")).contains("1");
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    @Test
    void leaderThatLosesTheIndexAtTheConclusionRereadsTheRevisionAndStillIndexesItsEvidence() {
        String vehicle = vehicle("conclusion-race");
        String queryKey = queryKey(vehicle);
        TestRequests.Request leader = request(vehicle);
        Map<String, AttributeValue> rival = Map.of(
                "pk", AttributeValue.fromS("REUSE#" + queryKey),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("REUSE"),
                "queryId", AttributeValue.fromS("q_" + "0".repeat(32)),
                "evidenceId", AttributeValue.fromS("ev_" + "0".repeat(32)),
                "resultType", AttributeValue.fromS("FOUND"),
                "reusableUntil", AttributeValue.fromS(Timestamps.format(TestClock.INSTANCE.instant().minusSeconds(1))),
                "revision", AttributeValue.fromN("7"),
                "policyVersion", AttributeValue.fromS(reuse.policyVersion()));
        doCallRealMethod().doAnswer(invocation -> {
            Object read = invocation.callRealMethod();
            DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(rival));
            return read;
        }).doCallRealMethod().when(reuseLedger).reuseEntry(queryKey);

        worker.apply(EnrichmentWorkerIT.sqsEvent(leader.body()));

        String evidenceId = resolved(leader.queryId(), leader).path("evidenceId").asString();
        Map<String, AttributeValue> index = item("REUSE#" + queryKey, "META");
        assertThat(attribute(index, "revision")).contains("8");
        assertThat(attribute(index, "evidenceId")).contains(evidenceId);
        assertThat(attribute(item("INFLIGHT#" + queryKey, "META"), "evidenceId")).contains(evidenceId);
        assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
    }

    /** Líder que caiu no HTTP: liderança e consulta em voo, com a tentativa aberta há mais que o timeout do worker. */
    private static void givenInterruptedLeader(String queryKey, TestRequests.Request leader) {
        String startedAt = Timestamps.format(TestClock.INSTANCE.instant().minusSeconds(200));
        givenLeadershipInFlight(queryKey, leader, TestClock.INSTANCE.instant().minusSeconds(170));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + leader.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "queryKey", AttributeValue.fromS(queryKey),
                "fencingVersion", AttributeValue.fromN("1"),
                "gsi2pk", AttributeValue.fromS("QUERY_IN_FLIGHT"),
                "gsi2sk", AttributeValue.fromS(startedAt))));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + leader.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(startedAt))));
    }

    /**
     * A liderança, a consulta em voo e a {@code ATTEMPT#0001} que outra entrega do mesmo pedido grava ao liderar, com a
     * lease válida.
     */
    private void givenLiveLeader(String queryKey, TestRequests.Request leader) {
        Instant now = TestClock.INSTANCE.instant();
        givenLeadershipInFlight(queryKey, leader, now.plus(reuse.lease()));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + leader.queryId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERY"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "queryKey", AttributeValue.fromS(queryKey),
                "fencingVersion", AttributeValue.fromN("1"),
                "gsi2pk", AttributeValue.fromS("QUERY_IN_FLIGHT"),
                "gsi2sk", AttributeValue.fromS(Timestamps.format(now)))));
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERY#" + leader.queryId()),
                "sk", AttributeValue.fromS("ATTEMPT#" + Identities.sequence(1)),
                "type", AttributeValue.fromS("ATTEMPT"),
                "sequence", AttributeValue.fromN("1"),
                "startedAt", AttributeValue.fromS(Timestamps.format(now)))));
    }

    /** Liderança em voo na versão 1, numa cadeia que começa nela. */
    private static void givenLeadershipInFlight(String queryKey, TestRequests.Request leader, Instant leaseUntil) {
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("INFLIGHT#" + queryKey),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("INFLIGHT"),
                "state", AttributeValue.fromS("IN_FLIGHT"),
                "fencingVersion", AttributeValue.fromN("1"),
                "firstFencingVersion", AttributeValue.fromN("1"),
                "leaderWorkRef", AttributeValue.fromS(leader.workRef()),
                "queryId", AttributeValue.fromS(leader.queryId()),
                "leaseUntil", AttributeValue.fromS(Timestamps.format(leaseUntil)))));
    }

    /** O estado da liderança, com a lease ainda válida e a evidência gravada pela conclusão. */
    private void setLeadershipState(String queryKey, String state) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS("INFLIGHT#" + queryKey), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET #state = :state, leaseUntil = :leaseUntil")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(
                        ":state", AttributeValue.fromS(state),
                        ":leaseUntil", AttributeValue.fromS(Timestamps.format(
                                TestClock.INSTANCE.instant().plus(reuse.lease()))))));
    }

    /** {@code CONSUMED} gravado por outra entrega do mesmo pedido, como o {@code HIT} o grava. */
    private static void givenConsumed(TestRequests.Request request) {
        DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                "pk", AttributeValue.fromS("CONSUMED#" + request.eventId()),
                "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("CONSUMED"),
                "workRef", AttributeValue.fromS(request.workRef()))));
    }

    private static void forgetReuseIndex(String queryKey) {
        DYNAMO_DB.deleteItem(builder -> builder.tableName(TABLE)
                .key(Map.of("pk", AttributeValue.fromS("REUSE#" + queryKey), "sk", AttributeValue.fromS("META"))));
    }

    /** Seguidor registrado na versão, como o worker o grava. */
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

    /** Pedido com prazo no relógio dos testes, para o avanço do relógio não vencer o prazo. */
    private static TestRequests.Request request(String vehicle) {
        return TestRequests.enrichment(vehicle, TestClock.INSTANCE.instant().plus(DEADLINE));
    }

    private static TestRequests.Request request(String vehicle, String driver) {
        return TestRequests.enrichment(vehicle, driver, TestClock.INSTANCE.instant().plus(DEADLINE),
                ENRICHMENT_PROVIDER);
    }

    private static String driver() {
        return "driver-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String vehicle(String scenario) {
        return "vehicle-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String queryKey(String vehicle) {
        return queryKey(vehicle, "driver-ana");
    }

    private String queryKey(String vehicle, String driver) {
        return QueryKeys.compute(queryKeySecret.value(), "org-acme", ENRICHMENT_PROVIDER, "VEHICLE_DRIVER_PROFILE",
                "1", vehicle, driver);
    }

    /** {@code EnrichmentResolved} gravado no outbox para o pedido, com o resultado da consulta {@code queryId}. */
    private JsonNode resolved(String queryId, TestRequests.Request recipient) {
        String eventId = EventIds.enrichmentResolved(queryId, recipient.workRef());
        Map<String, AttributeValue> outbox = item("OUTBOX#" + eventId, "META");
        assertThat(attribute(outbox, "detailType")).as("outbox %s", eventId).contains("EnrichmentResolved");
        JsonNode detail = objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
        assertThat(detail.path("eventId").asString()).isEqualTo(eventId);
        assertThat(detail.path("workRef").asString()).isEqualTo(recipient.workRef());
        return detail;
    }

    private static List<Map<String, AttributeValue>> partition(String queryId) {
        return DYNAMO_DB.query(builder -> builder
                        .tableName(TABLE)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("QUERY#" + queryId))))
                .items();
    }

    private static List<Map<String, AttributeValue>> followers(String queryKey) {
        return DYNAMO_DB.query(builder -> builder
                        .tableName(TABLE)
                        .consistentRead(true)
                        .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.fromS("INFLIGHT#" + queryKey),
                                ":prefix", AttributeValue.fromS("FOLLOWER#"))))
                .items();
    }
}

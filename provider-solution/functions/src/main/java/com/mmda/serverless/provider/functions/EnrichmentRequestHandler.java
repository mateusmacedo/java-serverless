package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.Digests;
import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryKeys;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.RetryPolicy;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentProtocol;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentReply;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentRequest;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResolved;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentVerdict;
import com.mmda.serverless.provider.domain.enrichment.Leadership;
import com.mmda.serverless.provider.domain.enrichment.ReusePlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Um {@code EnrichmentRequested} (desenho, §8 e §9): reentrega, prazo, reuso ({@code HIT}), espera da consulta
 * em voo ({@code JOIN}) ou liderança com quota, chamada com o retry do worker e conclusão com evidência,
 * índice de reuso, fan-out e {@code EnrichmentResolved}.
 */
@Component
class EnrichmentRequestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnrichmentRequestHandler.class);
    private static final int MAX_PLAN_ROUNDS = 5;

    private final QueryLedger ledger;
    private final ReuseLedger reuseLedger;
    private final EnrichmentResolutions resolutions;
    private final ProviderClients clients;
    private final RawResponses rawResponses;
    private final QueryKeySecret queryKeySecret;
    private final DelayedRedelivery redelivery;
    private final EventJson json;
    private final ProviderProperties properties;
    private final ReuseProperties reuse;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RandomGenerator jitter;

    EnrichmentRequestHandler(QueryLedger ledger, ReuseLedger reuseLedger, EnrichmentResolutions resolutions,
                             ProviderClients clients, RawResponses rawResponses, QueryKeySecret queryKeySecret,
                             DelayedRedelivery redelivery, EventJson json, ProviderProperties properties,
                             ReuseProperties reuse, RetryPolicy retryPolicy, Clock clock, Sleeper sleeper,
                             RandomGenerator jitter) {
        this.ledger = ledger;
        this.reuseLedger = reuseLedger;
        this.resolutions = resolutions;
        this.clients = clients;
        this.rawResponses = rawResponses;
        this.queryKeySecret = queryKeySecret;
        this.redelivery = redelivery;
        this.json = json;
        this.properties = properties;
        this.reuse = reuse;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    /**
     * Antes do índice de reuso, a reentrega confere, nesta ordem, a consulta do próprio {@code workRef} (foi
     * líder), o {@code CONSUMED} do pedido (já resolvido) e o registro como seguidor na cadeia corrente (§8).
     */
    void handle(String messageBody) {
        EnrichmentRequest request = json.detail(messageBody, EnrichmentRequest.class);
        String queryId = Identities.queryId(request.workRef());
        Optional<QueryRecord> existing = ledger.load(queryId);
        if (existing.isPresent()) {
            redelivered(request, existing.get(), messageBody);
            return;
        }
        if (ledger.isConsumed(EnrichmentResolutions.consumedId(request.workRef()))) {
            LOGGER.info("Enrichment request already resolved: workRef={} quoteId={}", request.workRef(), request.quoteId());
            return;
        }
        String queryKey = QueryKeys.compute(queryKeySecret.value(), request.organizationId(), request.provider(),
                request.operation(), request.contractVersion(), request.params().vehicleRef(),
                request.params().principalDriverRef());
        Optional<Leadership> leadership = reuseLedger.leadership(queryKey);
        if (leadership.isPresent() && reuseLedger.isFollower(queryKey, leadership.get(), request.workRef())) {
            followerRedelivered(request, leadership.get());
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(request.deadlineAt())) {
            concludeWithoutCall(request, queryId, queryKey, ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL, now);
            return;
        }
        plan(request, queryId, queryKey, leadership, messageBody);
    }

    /** Releituras com {@code ConsistentRead} até o pedido usar, esperar ou liderar a consulta da {@code queryKey}. */
    private void plan(EnrichmentRequest request, String queryId, String queryKey, Optional<Leadership> firstRead,
                      String messageBody) {
        Optional<Leadership> leadership = firstRead;
        for (int round = 1; round <= MAX_PLAN_ROUNDS; round++) {
            Instant now = clock.instant();
            ReusePlan plan = ReusePlan.of(reuseLedger.reuseEntry(queryKey), leadership, request.workRef(), now,
                    reuse.policyVersion());
            boolean done = switch (plan) {
                case ReusePlan.Hit hit -> {
                    resolutions.publishHit(request, hit.entry());
                    yield true;
                }
                case ReusePlan.Join join -> join(request, queryKey, join);
                case ReusePlan.Lead lead -> lead(request, queryId, queryKey, lead, messageBody, now);
                case ReusePlan.OwnLeadership ignored -> {
                    ownLeadership(request, queryId, messageBody);
                    yield true;
                }
            };
            if (done) {
                return;
            }
            leadership = reuseLedger.leadership(queryKey);
        }
        throw new IllegalStateException("The enrichment leadership kept changing: workRef=" + request.workRef());
    }

    private boolean lead(EnrichmentRequest request, String queryId, String queryKey, ReusePlan.Lead lead,
                         String messageBody, Instant now) {
        QuotaReservation quota = QuotaReservation.of(properties, request.provider(), now);
        switch (ledger.lead(draft(request, queryId, queryKey), lead, quota, now)) {
            case LEADING -> {
                LOGGER.info("Enrichment leadership acquired: queryId={} fencingVersion={}", queryId,
                        lead.fencingVersion());
                call(request, queryId, new LeaderTerm(queryKey, lead.fencingVersion(), lead.firstFencingVersion()), 1);
                return true;
            }
            case QUOTA_EXHAUSTED -> {
                quotaExhausted(request, queryId, queryKey, messageBody, quota, now);
                return true;
            }
            case QUERY_EXISTS -> {
                LOGGER.info("Enrichment query created by another delivery: queryId={}", queryId);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Registro como seguidor (§8). Cancelado o registro, a liderança é relida: concluída na versão em que o
     * pedido tentou entrar, ele publica para si o resultado gravado, inclusive {@code NOT_FOUND} e {@code FAILED}.
     */
    private boolean join(EnrichmentRequest request, String queryKey, ReusePlan.Join join) {
        ReuseLedger.Registration registration = reuseLedger.register(queryKey, join.fencingVersion(), request,
                clock.instant());
        if (registration != ReuseLedger.Registration.LEADERSHIP_CHANGED) {
            LOGGER.info("Enrichment request joined the shared query: workRef={} fencingVersion={}",
                    request.workRef(), join.fencingVersion());
            return true;
        }
        Optional<Leadership> leadership = reuseLedger.leadership(queryKey);
        if (leadership.isPresent() && leadership.get().answers(join.fencingVersion())) {
            resolutions.publishRecorded(request, leadership.get());
            return true;
        }
        return false;
    }

    private void ownLeadership(EnrichmentRequest request, String queryId, String messageBody) {
        QueryRecord query = ledger.load(queryId).orElseThrow(() -> new IllegalStateException(
                "The request leads the shared query but has no query: workRef=" + request.workRef()));
        redelivered(request, query, messageBody);
    }

    /** Seguidor já registrado: com a liderança concluída, publica para si; em voo, o fan-out o alcança. */
    private void followerRedelivered(EnrichmentRequest request, Leadership leadership) {
        if (leadership.state() == Leadership.State.COMPLETED) {
            resolutions.publishRecorded(request, leadership);
        } else {
            LOGGER.info("Enrichment request already waits for the shared query: workRef={}", request.workRef());
        }
    }

    private void call(EnrichmentRequest request, String queryId, LeaderTerm term, int firstSequence) {
        int sequence = firstSequence;
        while (true) {
            ProviderExchange<EnrichmentReply> exchange = clients.fetchProfile(queryId, request.params());
            EnrichmentVerdict verdict = EnrichmentProtocol.classify(exchange.outcome());
            if (!(verdict instanceof EnrichmentVerdict.Transient transientFailure)) {
                conclude(request, queryId, term, sequence, verdict, exchange);
                return;
            }
            Instant now = clock.instant();
            ledger.finishAttempt(queryId, new Settlement.AttemptResult(sequence, "TRANSIENT", exchange.httpStatus(),
                    transientFailure.errorClass(), null), now);
            LOGGER.warn("Enrichment attempt {} failed: queryId={} errorClass={}", sequence, queryId,
                    transientFailure.errorClass());
            if (sequence >= retryPolicy.maxAttempts()) {
                fail(request, queryId, term, sequence, ErrorClasses.PROVIDER_UNAVAILABLE);
                return;
            }
            sleeper.sleep(retryPolicy.backoffBefore(sequence, jitter.nextDouble()));
            now = clock.instant();
            if (!now.isBefore(request.deadlineAt())) {
                fail(request, queryId, term, sequence, ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL);
                return;
            }
            QuotaReservation quota = QuotaReservation.of(properties, request.provider(), now);
            QueryLedger.Reservation reservation = ledger.reserveNext(queryId, sequence + 1, quota, now);
            if (reservation == QueryLedger.Reservation.QUOTA_EXHAUSTED) {
                fail(request, queryId, term, sequence, ErrorClasses.QUOTA_EXHAUSTED);
                return;
            }
            if (reservation != QueryLedger.Reservation.RESERVED) {
                LOGGER.warn("Enrichment query is no longer in flight: queryId={}", queryId);
                return;
            }
            sequence++;
        }
    }

    private void conclude(EnrichmentRequest request, String queryId, LeaderTerm term, int sequence,
                          EnrichmentVerdict verdict, ProviderExchange<EnrichmentReply> exchange) {
        Instant now = clock.instant();
        String receivedAt = Timestamps.format(now);
        Resolution resolution = switch (verdict) {
            case EnrichmentVerdict.Found found -> {
                String observedAt = observedAt(exchange, receivedAt);
                yield new Resolution(QueryState.COMPLETED, observedAt, json.write(found.profile()),
                        EnrichmentResolved.Outcome.found(found.profile(), reusableUntil(observedAt, now)));
            }
            case EnrichmentVerdict.NotFound notFound -> new Resolution(QueryState.COMPLETED, receivedAt,
                    json.write(Map.of("notFoundRefs", notFound.notFoundRefs())),
                    EnrichmentResolved.Outcome.notFound(notFound.notFoundRefs(), reusableUntil(receivedAt, now)));
            case EnrichmentVerdict.Failed failed -> new Resolution(QueryState.FAILED, receivedAt, null,
                    EnrichmentResolved.Outcome.failed(failed.errorClass()));
            case EnrichmentVerdict.Unknown unknown -> new Resolution(QueryState.UNKNOWN, null, null,
                    EnrichmentResolved.Outcome.failed(unknown.errorClass()));
            case EnrichmentVerdict.Transient ignored ->
                    throw new IllegalStateException("A transient failure does not conclude the query");
        };
        String rawResponseRef = exchange.hasBody() ? rawResponses.store(queryId, sequence, exchange.rawBody()) : null;
        String outcomeName = resolution.state() == QueryState.UNKNOWN
                ? "NO_RESPONSE"
                : resolution.outcome().resultType().name();
        EvidenceDraft evidence = new EvidenceDraft(
                Identities.evidenceId(queryId, sequence),
                resolution.outcome().resultType().name(),
                resolution.normalizedResult(),
                rawResponseRef,
                resolution.observedAt(),
                receivedAt,
                resolution.outcome().reusableUntil(),
                exchange.hasBody() ? Digests.sha256Hex(exchange.rawBody()) : null,
                resolution.outcome().errorClass(),
                term.fencingVersion(),
                reuse.policyVersion());
        Settlement.AttemptResult attempt = new Settlement.AttemptResult(sequence, outcomeName, exchange.httpStatus(),
                resolution.outcome().errorClass(), null);
        resolutions.settle(request, queryId, null, resolution.state(), attempt, evidence, resolution.outcome(), term,
                now);
    }

    /** Conclui com {@code FAILED} depois das tentativas, que já foram encerradas uma a uma. */
    private void fail(EnrichmentRequest request, String queryId, LeaderTerm term, int lastSequence, String errorClass) {
        Instant now = clock.instant();
        resolutions.settle(request, queryId, null, QueryState.FAILED, null,
                failedEvidence(queryId, lastSequence, errorClass, term.fencingVersion(), now),
                EnrichmentResolved.Outcome.failed(errorClass), term, now);
    }

    /** Prazo vencido ou quota esgotada antes da primeira chamada: a consulta nasce concluída, sem liderança. */
    private void concludeWithoutCall(EnrichmentRequest request, String queryId, String queryKey, String errorClass,
                                     Instant now) {
        resolutions.settle(request, queryId, draft(request, queryId, queryKey), QueryState.FAILED, null,
                failedEvidence(queryId, 0, errorClass, null, now), EnrichmentResolved.Outcome.failed(errorClass), null,
                now);
    }

    /**
     * Reentrega da própria consulta (§8): concluída, refaz o fan-out se liderou; em voo, nunca repete a chamada.
     * Enquanto outra entrega pode estar viva, adia o pedido sem tocar a consulta. Depois disso, a consulta fica
     * {@code UNKNOWN} (com tentativa aberta) ou {@code FAILED}, e a liderança conclui com {@code FAILED} se
     * ainda for a dela.
     */
    private void redelivered(EnrichmentRequest request, QueryRecord query, String messageBody) {
        if (query.state().isSettled()) {
            if (query.isLeader()) {
                resolutions.redoFanOut(query, request.workRef());
            }
            LOGGER.info("Enrichment query already settled: queryId={} state={}", query.queryId(), query.state());
            return;
        }
        Instant now = clock.instant();
        if (deferredWhileBusy(query, messageBody, now)) {
            return;
        }
        boolean openAttempt = query.hasOpenAttempt();
        String errorClass = openAttempt ? ErrorClasses.INTERRUPTED_ATTEMPT : ErrorClasses.INTERRUPTED_BEFORE_CALL;
        resolutions.settle(request, query.queryId(), null, openAttempt ? QueryState.UNKNOWN : QueryState.FAILED, null,
                failedEvidence(query.queryId(), query.attemptCount(), errorClass, query.fencingVersion(), now),
                EnrichmentResolved.Outcome.failed(errorClass), interruptedTerm(query), now);
    }

    /**
     * A liderança da consulta interrompida, se a {@code fencingVersion} e o {@code queryId} do {@code INFLIGHT#}
     * ainda forem os dela.
     */
    private LeaderTerm interruptedTerm(QueryRecord query) {
        if (!query.isLeader()) {
            return null;
        }
        return reuseLedger.leadership(query.queryKey())
                .filter(leadership -> leadership.state() == Leadership.State.IN_FLIGHT
                        && leadership.fencingVersion() == query.fencingVersion()
                        && leadership.queryId().equals(query.queryId()))
                .map(leadership -> new LeaderTerm(query.queryKey(), leadership.fencingVersion(),
                        leadership.firstFencingVersion()))
                .orElse(null);
    }

    private boolean deferredWhileBusy(QueryRecord query, String messageBody, Instant now) {
        Optional<Instant> busyUntil = query.busyUntil(properties.workerTimeout());
        if (busyUntil.isEmpty() || !now.isBefore(busyUntil.get())) {
            return false;
        }
        int delay = (int) Duration.between(now, busyUntil.get()).toSeconds() + 1;
        redelivery.send(properties.enrichmentRequestsQueueUrl(), messageBody, delay);
        LOGGER.info("Enrichment query may be in progress in another delivery, request delayed {} s: queryId={}",
                delay, query.queryId());
        return true;
    }

    private void quotaExhausted(EnrichmentRequest request, String queryId, String queryKey, String messageBody,
                                QuotaReservation quota, Instant now) {
        long delay = quota.window().secondsUntilEnd(now) + 1;
        if (delay <= DelayedRedelivery.MAX_DELAY_SECONDS && now.plusSeconds(delay).isBefore(request.deadlineAt())) {
            redelivery.send(properties.enrichmentRequestsQueueUrl(), messageBody, (int) delay);
            LOGGER.info("Enrichment quota exhausted, request delayed {} s: queryId={}", delay, queryId);
            return;
        }
        concludeWithoutCall(request, queryId, queryKey, ErrorClasses.QUOTA_EXHAUSTED, now);
    }

    private EvidenceDraft failedEvidence(String queryId, int sequence, String errorClass, Long fencingVersion,
                                         Instant now) {
        return new EvidenceDraft(Identities.evidenceId(queryId, sequence), "FAILED", null, null, null,
                Timestamps.format(now), null, null, errorClass, fencingVersion, reuse.policyVersion());
    }

    /**
     * WHY: a idade da informação conta a partir do {@code observedAt} (RN05), mas um {@code observedAt} à frente
     * do recebimento (relógio do provedor adiantado) estenderia a validade do {@code HIT} além do parâmetro.
     */
    private String reusableUntil(String observedAt, Instant receivedAt) {
        Instant observed = Timestamps.parse(observedAt);
        Instant from = observed.isAfter(receivedAt) ? receivedAt : observed;
        return Timestamps.format(from.plusSeconds(properties.enrichmentValiditySeconds()));
    }

    private static String observedAt(ProviderExchange<EnrichmentReply> exchange, String fallback) {
        if (exchange.outcome() instanceof CallOutcome.Responded<EnrichmentReply> responded
                && responded.reply() != null && responded.reply().observedAt() != null) {
            try {
                return Timestamps.format(Timestamps.parse(responded.reply().observedAt()));
            } catch (DateTimeParseException exception) {
                return fallback;
            }
        }
        return fallback;
    }

    private static QueryDraft draft(EnrichmentRequest request, String queryId, String queryKey) {
        return new QueryDraft(queryId, QueryDraft.ENRICH, request.provider(), request.operation(),
                request.contractVersion(), request.organizationId(), request.workRef(), request.quoteId(),
                request.version(), null, queryKey, request.deadlineAt());
    }

    private record Resolution(QueryState state, String observedAt, String normalizedResult,
                              EnrichmentResolved.Outcome outcome) {
    }
}

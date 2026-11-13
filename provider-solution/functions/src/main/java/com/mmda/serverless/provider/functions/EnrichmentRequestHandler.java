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
import com.mmda.serverless.provider.domain.enrichment.ReuseDecision;
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
 * Um {@code EnrichmentRequested}: consulta pelo {@code workRef}, prazo, quota, chamada com o retry do
 * worker e conclusão com evidência e {@code EnrichmentResolved} (§8 e §9). No M1 toda consulta é
 * {@code MISS}; o M2 encaixa {@code REUSE#} e {@code INFLIGHT#} antes da reserva, sem mudar estes itens.
 */
@Component
class EnrichmentRequestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnrichmentRequestHandler.class);

    private final QueryLedger ledger;
    private final ProviderClients clients;
    private final RawResponses rawResponses;
    private final QueryKeySecret queryKeySecret;
    private final DelayedRedelivery redelivery;
    private final EventJson json;
    private final ProviderProperties properties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RandomGenerator jitter;

    EnrichmentRequestHandler(QueryLedger ledger, ProviderClients clients, RawResponses rawResponses,
                             QueryKeySecret queryKeySecret, DelayedRedelivery redelivery, EventJson json,
                             ProviderProperties properties, RetryPolicy retryPolicy, Clock clock, Sleeper sleeper,
                             RandomGenerator jitter) {
        this.ledger = ledger;
        this.clients = clients;
        this.rawResponses = rawResponses;
        this.queryKeySecret = queryKeySecret;
        this.redelivery = redelivery;
        this.json = json;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    void handle(String messageBody) {
        EnrichmentRequest request = json.detail(messageBody, EnrichmentRequest.class);
        String queryId = Identities.queryId(request.workRef());
        Optional<QueryRecord> existing = ledger.load(queryId);
        if (existing.isPresent()) {
            redelivered(request, existing.get(), messageBody);
            return;
        }
        if (ledger.isConsumed(request.eventId())) {
            LOGGER.info("Enrichment request already resolved: workRef={} quoteId={}", request.workRef(), request.quoteId());
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(request.deadlineAt())) {
            concludeWithoutCall(request, queryId, null, ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL, now);
            return;
        }
        String queryKey = QueryKeys.compute(queryKeySecret.value(), request.organizationId(), request.provider(),
                request.operation(), request.contractVersion(), request.params().vehicleRef(),
                request.params().principalDriverRef());
        QuotaReservation quota = QuotaReservation.of(properties, request.provider(), now);
        switch (ledger.reserveFirst(draft(request, queryId, queryKey), quota, now)) {
            case RESERVED -> call(request, queryId, 1);
            case QUOTA_EXHAUSTED -> quotaExhausted(request, queryId, queryKey, messageBody, quota, now);
            default -> LOGGER.info("Enrichment query created by another delivery: queryId={}", queryId);
        }
    }

    private void call(EnrichmentRequest request, String queryId, int firstSequence) {
        int sequence = firstSequence;
        while (true) {
            ProviderExchange<EnrichmentReply> exchange = clients.fetchProfile(queryId, request.params());
            EnrichmentVerdict verdict = EnrichmentProtocol.classify(exchange.outcome());
            if (!(verdict instanceof EnrichmentVerdict.Transient transientFailure)) {
                conclude(request, queryId, sequence, verdict, exchange);
                return;
            }
            Instant now = clock.instant();
            ledger.finishAttempt(queryId, new Settlement.AttemptResult(sequence, "TRANSIENT", exchange.httpStatus(),
                    transientFailure.errorClass(), null), now);
            LOGGER.warn("Enrichment attempt {} failed: queryId={} errorClass={}", sequence, queryId,
                    transientFailure.errorClass());
            if (sequence >= retryPolicy.maxAttempts()) {
                fail(request, queryId, sequence, ErrorClasses.PROVIDER_UNAVAILABLE);
                return;
            }
            sleeper.sleep(retryPolicy.backoffBefore(sequence, jitter.nextDouble()));
            now = clock.instant();
            if (!now.isBefore(request.deadlineAt())) {
                fail(request, queryId, sequence, ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL);
                return;
            }
            QuotaReservation quota = QuotaReservation.of(properties, request.provider(), now);
            QueryLedger.Reservation reservation = ledger.reserveNext(queryId, sequence + 1, quota, now);
            if (reservation == QueryLedger.Reservation.QUOTA_EXHAUSTED) {
                fail(request, queryId, sequence, ErrorClasses.QUOTA_EXHAUSTED);
                return;
            }
            if (reservation != QueryLedger.Reservation.RESERVED) {
                LOGGER.warn("Enrichment query is no longer in flight: queryId={}", queryId);
                return;
            }
            sequence++;
        }
    }

    private void conclude(EnrichmentRequest request, String queryId, int sequence, EnrichmentVerdict verdict,
                          ProviderExchange<EnrichmentReply> exchange) {
        Instant now = clock.instant();
        String receivedAt = Timestamps.format(now);
        Resolution resolution = switch (verdict) {
            case EnrichmentVerdict.Found found -> {
                String observedAt = observedAt(exchange, receivedAt);
                yield new Resolution(QueryState.COMPLETED, observedAt, json.write(found.profile()),
                        EnrichmentResolved.Outcome.found(found.profile(), reusableUntil(observedAt)));
            }
            case EnrichmentVerdict.NotFound notFound -> new Resolution(QueryState.COMPLETED, receivedAt,
                    json.write(Map.of("notFoundRefs", notFound.notFoundRefs())),
                    EnrichmentResolved.Outcome.notFound(notFound.notFoundRefs(), reusableUntil(receivedAt)));
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
                resolution.outcome().errorClass());
        Settlement.AttemptResult attempt = new Settlement.AttemptResult(sequence, outcomeName, exchange.httpStatus(),
                resolution.outcome().errorClass(), null);
        settle(request, queryId, null, resolution.state(), attempt, evidence, resolution.outcome(), now);
    }

    /** Conclui com {@code FAILED} depois das tentativas, que já foram encerradas uma a uma. */
    private void fail(EnrichmentRequest request, String queryId, int lastSequence, String errorClass) {
        Instant now = clock.instant();
        settle(request, queryId, null, QueryState.FAILED, null,
                failedEvidence(queryId, lastSequence, errorClass, now), EnrichmentResolved.Outcome.failed(errorClass), now);
    }

    /** Prazo vencido ou quota esgotada antes da primeira chamada: a consulta nasce concluída, sem chamada. */
    private void concludeWithoutCall(EnrichmentRequest request, String queryId, String queryKey, String errorClass,
                                     Instant now) {
        settle(request, queryId, draft(request, queryId, queryKey), QueryState.FAILED, null,
                failedEvidence(queryId, 0, errorClass, now), EnrichmentResolved.Outcome.failed(errorClass), now);
    }

    /**
     * Reentrega (§8): consulta concluída já tem o outbox; em voo, nunca repete a chamada. Enquanto outra
     * entrega do mesmo pedido pode estar viva (no HTTP ou no backoff), o pedido é adiado com
     * {@code DelaySeconds}, sem tocar a consulta. Depois disso, com tentativa aberta, a consulta fica
     * {@code UNKNOWN} para a reconciliação e a quote recebe {@code FAILED}.
     */
    private void redelivered(EnrichmentRequest request, QueryRecord query, String messageBody) {
        if (query.state().isSettled()) {
            LOGGER.info("Enrichment query already settled: queryId={} state={}", query.queryId(), query.state());
            return;
        }
        Instant now = clock.instant();
        if (deferredWhileBusy(query, messageBody, now)) {
            return;
        }
        boolean openAttempt = query.hasOpenAttempt();
        String errorClass = openAttempt ? ErrorClasses.INTERRUPTED_ATTEMPT : ErrorClasses.INTERRUPTED_BEFORE_CALL;
        settle(request, query.queryId(), null, openAttempt ? QueryState.UNKNOWN : QueryState.FAILED, null,
                failedEvidence(query.queryId(), query.attemptCount(), errorClass, now),
                EnrichmentResolved.Outcome.failed(errorClass), now);
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

    private void settle(EnrichmentRequest request, String queryId, QueryDraft newQuery, QueryState state,
                        Settlement.AttemptResult attempt, EvidenceDraft evidence, EnrichmentResolved.Outcome outcome,
                        Instant now) {
        EnrichmentResolved event = EnrichmentResolved.of(request, queryId, evidence.evidenceId(), ReuseDecision.MISS,
                outcome, Timestamps.format(now));
        Settlement settlement = new Settlement(queryId, newQuery, state, attempt, evidence,
                new Settlement.OutboxEvent(event.eventId(), EnrichmentResolved.DETAIL_TYPE, event),
                request.eventId(), request.workRef());
        if (ledger.settle(settlement, now)) {
            LOGGER.info("Enrichment resolved: queryId={} quoteId={} version={} resultType={} state={}",
                    queryId, request.quoteId(), request.version(), outcome.resultType(), state);
        } else {
            LOGGER.warn("Enrichment query was settled by another delivery: queryId={}", queryId);
        }
    }

    private EvidenceDraft failedEvidence(String queryId, int sequence, String errorClass, Instant now) {
        return new EvidenceDraft(Identities.evidenceId(queryId, sequence), "FAILED", null, null, null,
                Timestamps.format(now), null, null, errorClass);
    }

    private String reusableUntil(String observedAt) {
        return Timestamps.format(Timestamps.parse(observedAt).plusSeconds(properties.enrichmentValiditySeconds()));
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

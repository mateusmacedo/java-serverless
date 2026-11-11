package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Digests;
import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.RetryPolicy;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.InsurerProtocol;
import com.mmda.serverless.provider.domain.insurer.InsurerQuoteRequest;
import com.mmda.serverless.provider.domain.insurer.InsurerReply;
import com.mmda.serverless.provider.domain.insurer.InsurerResultRecorded;
import com.mmda.serverless.provider.domain.insurer.InsurerResultStatus;
import com.mmda.serverless.provider.domain.insurer.InsurerVerdict;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Um {@code InsurerQuoteRequested} (§8): {@code QUERY}, {@code ATTEMPT} e quota antes do HTTP; evidência e
 * outbox {@code InsurerResultRecorded} depois. A reentrega relê a consulta e nunca repete uma chamada que
 * pode ter chegado à seguradora.
 */
@Component
class InsurerRequestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsurerRequestHandler.class);

    private final QueryLedger ledger;
    private final ProviderClients clients;
    private final RawResponses rawResponses;
    private final DelayedRedelivery redelivery;
    private final EventJson json;
    private final ProviderProperties properties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RandomGenerator jitter;

    InsurerRequestHandler(QueryLedger ledger, ProviderClients clients, RawResponses rawResponses,
                          DelayedRedelivery redelivery, EventJson json, ProviderProperties properties,
                          RetryPolicy retryPolicy, Clock clock, Sleeper sleeper, RandomGenerator jitter) {
        this.ledger = ledger;
        this.clients = clients;
        this.rawResponses = rawResponses;
        this.redelivery = redelivery;
        this.json = json;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    void handle(String messageBody) {
        InsurerQuoteRequest request = json.detail(messageBody, InsurerQuoteRequest.class);
        String queryId = Identities.queryId(request.workRef());
        Optional<QueryRecord> existing = ledger.load(queryId);
        if (existing.isPresent()) {
            redelivered(request, existing.get(), messageBody);
            return;
        }
        Instant now = clock.instant();
        if (!now.isBefore(request.deadlineAt())) {
            concludeWithoutCall(request, queryId, ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL, now);
            return;
        }
        Optional<String> path = clients.insurerPath(request.insurerId());
        if (path.isEmpty()) {
            concludeWithoutCall(request, queryId, ErrorClasses.UNSUPPORTED_INSURER, now);
            return;
        }
        QuotaReservation quota = QuotaReservation.of(properties, request.insurerId(), now);
        switch (ledger.reserveFirst(draft(request, queryId), quota, now)) {
            case RESERVED -> call(request, queryId, path.get(), 1, messageBody);
            case QUOTA_EXHAUSTED -> quotaExhausted(request, queryId, messageBody, quota, now);
            default -> LOGGER.info("Insurer query created by another delivery: queryId={}", queryId);
        }
    }

    private void call(InsurerQuoteRequest request, String queryId, String path, int firstSequence,
                      String messageBody) {
        int sequence = firstSequence;
        while (true) {
            ProviderExchange<InsurerReply> exchange = clients.requestQuote(path, queryId, request);
            InsurerVerdict verdict = InsurerProtocol.classify(exchange.outcome());
            if (!(verdict instanceof InsurerVerdict.Transient transientFailure)) {
                conclude(request, queryId, sequence, verdict, exchange);
                return;
            }
            Instant now = clock.instant();
            ledger.finishAttempt(queryId, new Settlement.AttemptResult(sequence, "TRANSIENT", exchange.httpStatus(),
                    transientFailure.errorClass(), null), now);
            LOGGER.warn("Insurer attempt {} failed: queryId={} insurerId={} errorClass={}", sequence, queryId,
                    request.insurerId(), transientFailure.errorClass());
            if (sequence >= retryPolicy.maxAttempts()) {
                unavailable(request, queryId, sequence, ErrorClasses.PROVIDER_UNAVAILABLE);
                return;
            }
            sleeper.sleep(retryPolicy.backoffBefore(sequence, jitter.nextDouble()));
            now = clock.instant();
            if (!now.isBefore(request.deadlineAt())) {
                unavailable(request, queryId, sequence, ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL);
                return;
            }
            QuotaReservation quota = QuotaReservation.of(properties, request.insurerId(), now);
            QueryLedger.Reservation reservation = ledger.reserveNext(queryId, sequence + 1, quota, now);
            if (reservation == QueryLedger.Reservation.QUOTA_EXHAUSTED) {
                quotaExhaustedOnRetry(request, queryId, sequence, messageBody, quota, now);
                return;
            }
            if (reservation != QueryLedger.Reservation.RESERVED) {
                LOGGER.warn("Insurer query is no longer in flight: queryId={}", queryId);
                return;
            }
            sequence++;
        }
    }

    private void conclude(InsurerQuoteRequest request, String queryId, int sequence, InsurerVerdict verdict,
                          ProviderExchange<InsurerReply> exchange) {
        Instant now = clock.instant();
        if (verdict instanceof InsurerVerdict.Unknown unknown) {
            Settlement.AttemptResult attempt = new Settlement.AttemptResult(sequence, "NO_RESPONSE", null,
                    unknown.errorClass(), null);
            ledger.settle(new Settlement(queryId, null, QueryState.UNKNOWN, attempt, null, null, null,
                    request.workRef()), now);
            LOGGER.warn("Insurer did not answer, query left for reconciliation: queryId={} insurerId={}",
                    queryId, request.insurerId());
            return;
        }
        if (verdict instanceof InsurerVerdict.Accepted accepted) {
            ledger.recordAccepted(queryId, new Settlement.AttemptResult(sequence, "ACCEPTED", exchange.httpStatus(),
                    null, accepted.providerRequestId()), now);
            LOGGER.info("Insurer accepted the request: queryId={} insurerId={}", queryId, request.insurerId());
            return;
        }
        String rawResponseRef = exchange.hasBody() ? rawResponses.store(queryId, sequence, exchange.rawBody()) : null;
        InsurerResultRecorded.Outcome outcome = switch (verdict) {
            case InsurerVerdict.Quoted quoted -> InsurerResultRecorded.Outcome.quoted(
                    quoted.offer().withRawResponseRef(rawResponseRef));
            case InsurerVerdict.Declined declined -> InsurerResultRecorded.Outcome.declined(declined.reasonCode());
            case InsurerVerdict.NeedsInformation needs -> InsurerResultRecorded.Outcome.needsInformation(
                    needs.reasonCode(), needs.missingFields());
            case InsurerVerdict.Unavailable unavailable ->
                    InsurerResultRecorded.Outcome.unavailable(unavailable.reasonCode());
            default -> throw new IllegalStateException("Unexpected verdict " + verdict);
        };
        String receivedAt = Timestamps.format(now);
        EvidenceDraft evidence = new EvidenceDraft(
                Identities.evidenceId(queryId, sequence),
                outcome.status().name(),
                json.write(normalizedResult(outcome)),
                rawResponseRef,
                receivedAt,
                receivedAt,
                null,
                exchange.hasBody() ? Digests.sha256Hex(exchange.rawBody()) : null,
                outcome.status() == InsurerResultStatus.UNAVAILABLE
                        ? outcome.reasonCode()
                        : null);
        Settlement.AttemptResult attempt = new Settlement.AttemptResult(sequence, outcome.status().name(),
                exchange.httpStatus(), evidence.errorClass(), null);
        QueryState state = outcome.status() == InsurerResultStatus.UNAVAILABLE
                ? QueryState.FAILED
                : QueryState.COMPLETED;
        settle(request, queryId, null, state, attempt, evidence, Identities.attemptRef(queryId, sequence), outcome, now);
    }

    private void unavailable(InsurerQuoteRequest request, String queryId, int lastSequence, String reasonCode) {
        Instant now = clock.instant();
        settle(request, queryId, null, QueryState.FAILED, null, unavailableEvidence(queryId, lastSequence, reasonCode, now),
                Identities.attemptRef(queryId, lastSequence), InsurerResultRecorded.Outcome.unavailable(reasonCode), now);
    }

    private void concludeWithoutCall(InsurerQuoteRequest request, String queryId, String reasonCode, Instant now) {
        settle(request, queryId, draft(request, queryId), QueryState.FAILED, null,
                unavailableEvidence(queryId, 0, reasonCode, now), null,
                InsurerResultRecorded.Outcome.unavailable(reasonCode), now);
    }

    /**
     * Reentrega pela tabela do §8: concluída ou {@code UNKNOWN} não faz nada; enquanto outra entrega pode estar
     * viva, o pedido é adiado sem tocar a consulta; depois disso, com tentativa aberta, a consulta fica
     * {@code UNKNOWN}; só com falhas antes do envio, segue com nova tentativa se couber no orçamento.
     */
    private void redelivered(InsurerQuoteRequest request, QueryRecord query, String messageBody) {
        String queryId = query.queryId();
        if (query.state().isSettled()) {
            LOGGER.info("Insurer query already settled: queryId={} state={}", queryId, query.state());
            return;
        }
        if (query.providerRequestId() != null) {
            LOGGER.info("Insurer query waits for the callback: queryId={}", queryId);
            return;
        }
        Instant now = clock.instant();
        if (deferredWhileBusy(query, messageBody, now)) {
            return;
        }
        if (query.hasOpenAttempt()) {
            ledger.settle(new Settlement(queryId, null, QueryState.UNKNOWN, null, null, null, null,
                    request.workRef()), now);
            LOGGER.warn("Interrupted insurer attempt, query left for reconciliation: queryId={}", queryId);
            return;
        }
        int next = query.attemptCount() + 1;
        Optional<String> path = clients.insurerPath(request.insurerId());
        if (next > retryPolicy.maxAttempts() || path.isEmpty()) {
            unavailable(request, queryId, query.attemptCount(), ErrorClasses.PROVIDER_UNAVAILABLE);
            return;
        }
        if (!now.isBefore(request.deadlineAt())) {
            unavailable(request, queryId, query.attemptCount(), ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL);
            return;
        }
        QuotaReservation quota = QuotaReservation.of(properties, request.insurerId(), now);
        switch (ledger.reserveNext(queryId, next, quota, now)) {
            case RESERVED -> call(request, queryId, path.get(), next, messageBody);
            case QUOTA_EXHAUSTED -> quotaExhaustedOnRetry(request, queryId, query.attemptCount(), messageBody, quota, now);
            default -> LOGGER.info("Insurer query changed by another delivery: queryId={}", queryId);
        }
    }

    /**
     * Uma entrega duplicada do mesmo pedido (a publicação é pelo menos uma vez) pode estar no HTTP ou no
     * backoff. A consulta não é tocada, e o pedido volta depois que essa entrega terminou ou morreu, com
     * {@code DelaySeconds}, sem gastar {@code maxReceiveCount}.
     */
    private boolean deferredWhileBusy(QueryRecord query, String messageBody, Instant now) {
        Optional<Instant> busyUntil = query.busyUntil(properties.workerTimeout());
        if (busyUntil.isEmpty() || !now.isBefore(busyUntil.get())) {
            return false;
        }
        int delay = (int) Duration.between(now, busyUntil.get()).toSeconds() + 1;
        redelivery.send(properties.insurerRequestsQueueUrl(), messageBody, delay);
        LOGGER.info("Insurer query may be in progress in another delivery, request delayed {} s: queryId={}", delay,
                query.queryId());
        return true;
    }

    private void quotaExhausted(InsurerQuoteRequest request, String queryId, String messageBody, QuotaReservation quota,
                                Instant now) {
        if (delayUntilWindowTurns(request, messageBody, quota, now)) {
            LOGGER.info("Insurer quota exhausted, request delayed: queryId={} insurerId={}", queryId, request.insurerId());
            return;
        }
        concludeWithoutCall(request, queryId, ErrorClasses.QUOTA_EXHAUSTED, now);
    }

    /** Com a consulta já em voo e sem tentativa aberta, a reentrega adiada continua de onde parou. */
    private void quotaExhaustedOnRetry(InsurerQuoteRequest request, String queryId, int lastSequence, String messageBody,
                                       QuotaReservation quota, Instant now) {
        if (delayUntilWindowTurns(request, messageBody, quota, now)) {
            LOGGER.info("Insurer quota exhausted on retry, request delayed: queryId={}", queryId);
            return;
        }
        unavailable(request, queryId, lastSequence, ErrorClasses.QUOTA_EXHAUSTED);
    }

    private boolean delayUntilWindowTurns(InsurerQuoteRequest request, String messageBody, QuotaReservation quota,
                                          Instant now) {
        long delay = quota.window().secondsUntilEnd(now) + 1;
        if (delay > DelayedRedelivery.MAX_DELAY_SECONDS || !now.plusSeconds(delay).isBefore(request.deadlineAt())) {
            return false;
        }
        redelivery.send(properties.insurerRequestsQueueUrl(), messageBody, (int) delay);
        return true;
    }

    private void settle(InsurerQuoteRequest request, String queryId, QueryDraft newQuery, QueryState state,
                        Settlement.AttemptResult attempt, EvidenceDraft evidence, String attemptRef,
                        InsurerResultRecorded.Outcome outcome, Instant now) {
        InsurerResultRecorded event = InsurerResultRecorded.of(request, queryId, attemptRef, outcome,
                Timestamps.format(now));
        Settlement settlement = new Settlement(queryId, newQuery, state, attempt, evidence,
                new Settlement.OutboxEvent(event.eventId(), InsurerResultRecorded.DETAIL_TYPE, event),
                request.eventId(), request.workRef());
        if (ledger.settle(settlement, now)) {
            LOGGER.info("Insurer result recorded: queryId={} quoteId={} version={} insurerId={} status={}",
                    queryId, request.quoteId(), request.version(), request.insurerId(), outcome.status());
        } else {
            LOGGER.warn("Insurer query was settled by another delivery: queryId={}", queryId);
        }
    }

    private static Object normalizedResult(InsurerResultRecorded.Outcome outcome) {
        if (outcome.offer() != null) {
            return outcome.offer();
        }
        return outcome.missingFields() == null
                ? Map.of("reasonCode", outcome.reasonCode())
                : Map.of("reasonCode", outcome.reasonCode(), "missingFields", outcome.missingFields());
    }

    private EvidenceDraft unavailableEvidence(String queryId, int sequence, String reasonCode, Instant now) {
        String receivedAt = Timestamps.format(now);
        return new EvidenceDraft(Identities.evidenceId(queryId, sequence), "UNAVAILABLE",
                json.write(Map.of("reasonCode", reasonCode)), null, null, receivedAt, null, null, reasonCode);
    }

    private static QueryDraft draft(InsurerQuoteRequest request, String queryId) {
        return new QueryDraft(queryId, QueryDraft.QUOTE, request.insurerId(), null, null, request.organizationId(),
                request.workRef(), request.quoteId(), request.version(), request.insurerId(), null,
                request.deadlineAt());
    }
}

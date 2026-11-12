package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Digests;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.Correlation;
import com.mmda.serverless.provider.domain.insurer.InsurerCallback;
import com.mmda.serverless.provider.domain.insurer.InsurerProtocol;
import com.mmda.serverless.provider.domain.insurer.InsurerResultRecorded;
import com.mmda.serverless.provider.domain.insurer.InsurerResultStatus;
import com.mmda.serverless.provider.domain.insurer.InsurerVerdict;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Metade do worker no encontro do {@code CORR#} (§8) e conclusão dos callbacks que a API guardou antes dela.
 * O {@code CONSUMED} do pedido vai na transação que grava o resultado do callback, nunca na do {@code 202}.
 */
@Component
class PendingCallbacks {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingCallbacks.class);

    private final CallbackLedger callbacks;
    private final QueryLedger ledger;
    private final RawResponses rawResponses;
    private final EventJson json;
    private final Clock clock;

    PendingCallbacks(CallbackLedger callbacks, QueryLedger ledger, RawResponses rawResponses, EventJson json,
                     Clock clock) {
        this.callbacks = callbacks;
        this.ledger = ledger;
        this.rawResponses = rawResponses;
        this.json = json;
        this.clock = clock;
    }

    /** Idempotente: a reentrega do pedido com {@code providerRequestId} refaz exatamente este passo. */
    void meet(String provider, String providerRequestId, String queryId) {
        Optional<Correlation> correlation = callbacks.joinAsWorker(provider, providerRequestId, queryId,
                clock.instant());
        if (correlation.isEmpty()) {
            LOGGER.error("providerRequestId is already correlated to another query: provider={} queryId={}", provider,
                    queryId);
            return;
        }
        if (!correlation.get().bothHalves()) {
            LOGGER.info("Insurer query waits for the callback: queryId={} provider={}", queryId, provider);
            return;
        }
        QueryRecord query = ledger.load(queryId).orElseThrow(() ->
                new IllegalStateException("The correlated query " + queryId + " does not exist"));
        for (String externalEventId : new TreeSet<>(correlation.get().pendingEvents())) {
            process(query, provider, externalEventId);
        }
    }

    private void process(QueryRecord query, String provider, String externalEventId) {
        Optional<CallbackLedger.InboxEntry> inbox = callbacks.loadInbox(provider, externalEventId);
        if (inbox.isEmpty() || !inbox.get().received()) {
            return;
        }
        Instant now = clock.instant();
        if (!query.state().equals(QueryState.IN_FLIGHT) && !query.state().equals(QueryState.UNKNOWN)) {
            callbacks.markProcessed(provider, externalEventId, query.queryId(), now);
            LOGGER.info("Callback for a settled query marked processed: queryId={} externalEventId={}",
                    query.queryId(), externalEventId);
            return;
        }
        CallbackLedger.Processing processing = callbacks.settle(settlement(query, provider, inbox.get(), now), now);
        LOGGER.info("Pending callback processed by the worker: queryId={} externalEventId={} processing={}",
                query.queryId(), externalEventId, processing);
    }

    private CallbackLedger.CallbackSettlement settlement(QueryRecord query, String provider,
                                                         CallbackLedger.InboxEntry inbox, Instant now) {
        String queryId = query.queryId();
        InsurerCallback callback = json.readOrNull(inbox.payload(), InsurerCallback.class);
        InsurerVerdict verdict = InsurerProtocol.classifyCallback(callback == null ? null : callback.result());
        Optional<QueryRecord.Attempt> attempt = query.callbackAttempt(inbox.providerRequestId());
        int sequence = attempt.map(QueryRecord.Attempt::sequence).orElse(0);
        String rawResponseRef = rawResponses.storeCallback(queryId, inbox.externalEventId(), inbox.payload());
        InsurerResultRecorded.Outcome outcome = InsurerResultRecorded.Outcome.of(verdict, rawResponseRef);
        String receivedAt = inbox.receivedAt() == null ? Timestamps.format(now) : inbox.receivedAt();
        String errorClass = outcome.status() == InsurerResultStatus.UNAVAILABLE ? outcome.reasonCode() : null;
        EvidenceDraft evidence = new EvidenceDraft(Identities.evidenceId(queryId, sequence), outcome.status().name(),
                json.write(outcome.evidenceBody()), rawResponseRef, receivedAt, receivedAt, null,
                Digests.sha256Hex(inbox.payload()), errorClass);
        String attemptRef = attempt.isPresent() ? Identities.attemptRef(queryId, sequence) : null;
        InsurerResultRecorded event = InsurerResultRecorded.of(query.origin(), queryId, attemptRef, outcome,
                Timestamps.format(now));
        Settlement.AttemptResult finished = attempt.filter(open -> open.finishedAt() == null)
                .map(open -> new Settlement.AttemptResult(open.sequence(), outcome.status().name(), null, errorClass,
                        inbox.providerRequestId()))
                .orElse(null);
        String consumedEventId = query.requestEventId() != null
                ? query.requestEventId()
                : EventIds.request(query.workRef());
        return new CallbackLedger.CallbackSettlement(provider, inbox.externalEventId(), queryId,
                outcome.status() == InsurerResultStatus.UNAVAILABLE ? QueryState.FAILED : QueryState.COMPLETED,
                finished, evidence, new Settlement.OutboxEvent(event.eventId(), InsurerResultRecorded.DETAIL_TYPE, event),
                consumedEventId, query.workRef());
    }
}

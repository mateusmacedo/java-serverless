package com.mmda.serverless.provider.api;

import com.mmda.serverless.provider.domain.Digests;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.Correlation;
import com.mmda.serverless.provider.domain.insurer.InsurerCallback;
import com.mmda.serverless.provider.domain.insurer.InsurerProtocol;
import com.mmda.serverless.provider.domain.insurer.InsurerReply;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox dos callbacks (§8): grava o callback com {@code attribute_not_exists},
 * encontra a consulta pelo
 * {@code clientReference} ou pelo encontro no {@code CORR#} e conclui a
 * consulta numa transação idempotente.
 * O {@code CONSUMED} do pedido vai nessa transação, junto do outbox, nunca
 * antes.
 */
@Component
class CallbackInbox {

    private static final Logger LOGGER = LoggerFactory.getLogger(CallbackInbox.class);

    private final CallbackTable table;
    private final RawCallbacks rawCallbacks;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CallbackInbox(CallbackTable table, RawCallbacks rawCallbacks, ObjectMapper objectMapper, Clock clock) {
        this.table = table;
        this.rawCallbacks = rawCallbacks;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * {@code processingStatus} é {@code PROCESSED} quando o resultado já está
     * gravado; senão, {@code RECEIVED}.
     */
    record Receipt(String externalEventId, String processingStatus, boolean duplicate) {

        boolean processed() {
            return CallbackTable.PROCESSED.equals(processingStatus);
        }
    }

    /**
     * Um callback repetido é reconhecido pela inbox. Se ele já foi processado, nada
     * muda; se ainda está
     * {@code RECEIVED}, a associação é refeita, porque a entrega anterior pode ter
     * caído antes dela.
     */
    Receipt receive(String provider, CallbackEnvelope callback, String payload) {
        Instant now = clock.instant();
        String externalEventId = callback.externalEventId();
        boolean created = table.putInbox(provider, callback, payload, now);
        if (!created && table.loadInbox(provider, externalEventId).map(inbox -> !inbox.received()).orElse(false)) {
            LOGGER.info("Duplicate callback already processed: provider={} externalEventId={}", provider,
                    externalEventId);
            return new Receipt(externalEventId, CallbackTable.PROCESSED, true);
        }
        boolean processed = correlate(provider, callback, now);
        return new Receipt(externalEventId, processed ? CallbackTable.PROCESSED : CallbackTable.RECEIVED, !created);
    }

    private boolean correlate(String provider, CallbackEnvelope callback, Instant now) {
        if (callback.clientReference() != null) {
            Optional<CallbackTable.QueryView> query = table.loadQuery(callback.clientReference());
            if (query.isPresent() && belongs(query.get(), provider, callback.providerRequestId())) {
                process(query.get(), provider, callback.externalEventId());
                return true;
            }
            LOGGER.warn("Callback clientReference does not match a query of the provider: provider={} "
                    + "externalEventId={}", provider, callback.externalEventId());
        }
        if (callback.providerRequestId() == null) {
            return false;
        }
        Correlation correlation = table.addPending(provider, callback.providerRequestId(), callback.externalEventId(),
                now);
        if (!correlation.bothHalves()) {
            LOGGER.info("Callback held in the inbox until the worker half: provider={} externalEventId={}", provider,
                    callback.externalEventId());
            return false;
        }
        Optional<CallbackTable.QueryView> query = table.loadQuery(correlation.queryId())
                .filter(found -> belongs(found, provider, callback.providerRequestId()));
        if (query.isEmpty()) {
            LOGGER.error("Correlated query does not belong to the callback: provider={} queryId={}", provider,
                    correlation.queryId());
            return false;
        }
        for (String externalEventId : new TreeSet<>(correlation.pendingEvents())) {
            process(query.get(), provider, externalEventId);
        }
        return true;
    }

    private static boolean belongs(CallbackTable.QueryView query, String provider, String providerRequestId) {
        return provider.equals(query.insurerId())
                && (query.providerRequestId() == null || providerRequestId == null
                        || query.providerRequestId().equals(providerRequestId));
    }

    private void process(CallbackTable.QueryView query, String provider, String externalEventId) {
        Optional<CallbackTable.InboxEntry> inbox = table.loadInbox(provider, externalEventId);
        if (inbox.isEmpty() || !inbox.get().received()) {
            return;
        }
        Instant now = clock.instant();
        if (!query.settleable()) {
            table.markProcessed(provider, externalEventId, query.queryId(), now);
            LOGGER.info("Callback for a settled query marked processed: queryId={} externalEventId={}",
                    query.queryId(), externalEventId);
            return;
        }
        CallbackTable.Processing processing = table.settle(settlement(query, provider, inbox.get(), now), now);
        LOGGER.info("Callback processed: queryId={} externalEventId={} processing={}", query.queryId(),
                externalEventId, processing);
    }

    private CallbackTable.CallbackSettlement settlement(CallbackTable.QueryView query, String provider,
            CallbackTable.InboxEntry inbox, Instant now) {
        String queryId = query.queryId();
        InsurerVerdict verdict = InsurerProtocol.classifyCallback(result(inbox.payload()));
        Optional<CallbackTable.Attempt> attempt = query.callbackAttempt(inbox.providerRequestId());
        int sequence = attempt.map(CallbackTable.Attempt::sequence).orElse(0);
        String rawResponseRef = rawCallbacks.store(queryId, inbox.externalEventId(), inbox.payload());
        InsurerResultRecorded.Outcome outcome = InsurerResultRecorded.Outcome.of(verdict, rawResponseRef);
        boolean unavailable = outcome.status() == InsurerResultStatus.UNAVAILABLE;
        String errorClass = unavailable ? outcome.reasonCode() : null;
        String receivedAt = inbox.receivedAt() == null ? Timestamps.format(now) : inbox.receivedAt();
        InsurerResultRecorded event = InsurerResultRecorded.of(
                new InsurerResultRecorded.Origin(query.workRef(), query.quoteId(), query.version(), query.insurerId()),
                queryId, attempt.isPresent() ? Identities.attemptRef(queryId, sequence) : null, outcome,
                Timestamps.format(now));
        Integer openAttempt = attempt.filter(found -> found.finishedAt() == null)
                .map(CallbackTable.Attempt::sequence)
                .orElse(null);
        return new CallbackTable.CallbackSettlement(provider, inbox.externalEventId(), queryId,
                unavailable ? QueryState.FAILED : QueryState.COMPLETED,
                openAttempt, outcome.status().name(), errorClass, inbox.providerRequestId(),
                CallbackTable.evidence(queryId, Identities.evidenceId(queryId, sequence), outcome.status().name(),
                        objectMapper.writeValueAsString(outcome.evidenceBody()), rawResponseRef, receivedAt,
                        Digests.sha256Hex(inbox.payload()), errorClass),
                event.eventId(), InsurerResultRecorded.DETAIL_TYPE, objectMapper.writeValueAsString(event),
                query.requestEventId() != null ? query.requestEventId() : EventIds.request(query.workRef()),
                query.workRef());
    }

    /**
     * Resultado guardado na inbox; ilegível vira {@code null}, que a classificação
     * trata como {@code UNAVAILABLE}.
     */
    private InsurerReply result(String payload) {
        try {
            InsurerCallback callback = objectMapper.readValue(payload, InsurerCallback.class);
            return callback == null ? null : callback.result();
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }
}

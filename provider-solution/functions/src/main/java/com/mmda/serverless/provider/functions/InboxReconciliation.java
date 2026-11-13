package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.IssueType;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Inbox parada (desenho, §8 e §9): o callback {@code RECEIVED} refaz o encontro
 * com o código do worker e, sem
 * correlação depois de {@code unmatchedCallbackAfterSeconds}, vira
 * {@code UNMATCHED} com
 * {@code UNMATCHED_CALLBACK}. WHY: o {@code UNMATCHED} tenta de novo a cada
 * ciclo, porque a metade do worker no
 * {@code CORR#} pode chegar depois.
 */
@Component
class InboxReconciliation {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxReconciliation.class);

    private final CallbackLedger callbacks;
    private final PendingCallbacks pendingCallbacks;
    private final QueryLedger ledger;
    private final OperationIssues issues;
    private final OperationsProperties operations;

    InboxReconciliation(CallbackLedger callbacks, PendingCallbacks pendingCallbacks, QueryLedger ledger,
            OperationIssues issues, OperationsProperties operations) {
        this.callbacks = callbacks;
        this.pendingCallbacks = pendingCallbacks;
        this.ledger = ledger;
        this.issues = issues;
        this.operations = operations;
    }

    /**
     * @param unmatched callbacks {@code UNMATCHED} ao fim do ciclo, que viram a
     *                  métrica {@code UnmatchedCallbacks}
     */
    record Report(int processed, int newlyUnmatched, int unmatched) {
    }

    Report run(int limit, Instant now) {
        int processed = 0;
        int newlyUnmatched = 0;
        int unmatched = 0;
        String unmatchedBefore = Timestamps.format(now.minus(operations.unmatchedCallbackAfter()));
        for (CallbackLedger.InboxEntry entry : callbacks.inboxIndexed(Items.INBOX_RECEIVED,
                now.minus(operations.inboxStuckAfter()), limit)) {
            if (matched(entry)) {
                processed++;
            } else if (entry.receivedAt() != null && entry.receivedAt().compareTo(unmatchedBefore) < 0
                    && callbacks.markUnmatched(entry.provider(), entry.externalEventId(), now)) {
                newlyUnmatched++;
                unmatched++;
                issues.raise(new OperationIssues.Observation(IssueType.UNMATCHED_CALLBACK, target(entry), null, null,
                        null, "Callback de %s sem consulta correspondente".formatted(entry.provider())), false);
                LOGGER.warn("Callback without correlation marked UNMATCHED: provider={} externalEventId={}",
                        entry.provider(), entry.externalEventId());
            }
        }
        for (CallbackLedger.InboxEntry entry : callbacks.inboxIndexed(Items.INBOX_UNMATCHED, now.plusSeconds(1),
                limit)) {
            if (matched(entry)) {
                processed++;
            } else {
                unmatched++;
                if (entry.provider() != null) {
                    callbacks.deferUnmatched(entry.provider(), entry.externalEventId(), now);
                }
            }
        }
        return new Report(processed, newlyUnmatched, unmatched);
    }

    /**
     * Alvo do {@code UNMATCHED_CALLBACK}: o callback, identificado pelo provedor e
     * pelo {@code externalEventId}.
     */
    static String target(CallbackLedger.InboxEntry entry) {
        return entry.provider() + "#" + entry.externalEventId();
    }

    /**
     * O encontro do §8, na mesma ordem da {@code api}: o {@code clientReference},
     * depois o {@code CORR#}.
     */
    private boolean matched(CallbackLedger.InboxEntry entry) {
        if (entry.provider() == null) {
            return false;
        }
        try {
            Optional<QueryRecord> query = Optional.ofNullable(entry.clientReference())
                    .flatMap(ledger::load)
                    .filter(found -> belongs(found, entry));
            if (query.isEmpty() && entry.providerRequestId() != null) {
                query = callbacks.correlatedQuery(entry.provider(), entry.providerRequestId())
                        .flatMap(ledger::load)
                        .filter(found -> belongs(found, entry));
            }
            if (query.isEmpty()) {
                return false;
            }
            boolean processed = pendingCallbacks.process(query.get(), entry.provider(), entry.externalEventId());
            LOGGER.info("Stuck callback processed by the reconciler: queryId={} externalEventId={} processed={}",
                    query.get().queryId(), entry.externalEventId(), processed);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("Stuck callback could not be processed, retried in the next cycle: externalEventId={}",
                    entry.externalEventId(), exception);
            return false;
        }
    }

    private static boolean belongs(QueryRecord query, CallbackLedger.InboxEntry entry) {
        return entry.provider().equals(query.insurerId())
                && (query.providerRequestId() == null || entry.providerRequestId() == null
                        || query.providerRequestId().equals(entry.providerRequestId()));
    }
}

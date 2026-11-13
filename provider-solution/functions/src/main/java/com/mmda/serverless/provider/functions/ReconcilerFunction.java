package com.mmda.serverless.provider.functions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reconciler agendado (desenho, §7 e §9), a cada minuto: republica o
 * {@code OUTBOX_PENDING} (M1) e abre
 * {@code STALE_OUTBOX}; refaz o fan-out que um líder interrompido não terminou;
 * trata as consultas em voo além do
 * prazo e as {@code UNKNOWN}; processa a inbox parada e marca
 * {@code UNMATCHED}; reprocessa as DLQs; e resolve as
 * ocorrências cujo problema sumiu. Cada dever roda isolado: a falha de um não
 * impede os outros no ciclo.
 */
@Component("reconciler")
class ReconcilerFunction implements Function<ReconcileCommand, ReconcileResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconcilerFunction.class);

    private final OutboxReconciliation outbox;
    private final FanOutRecovery fanOuts;
    private final QueryReconciliation queries;
    private final InboxReconciliation inbox;
    private final DeadLetterReprocessor deadLetters;
    private final IssueReview issueReview;
    private final Metrics metrics;
    private final Clock clock;

    ReconcilerFunction(OutboxReconciliation outbox, FanOutRecovery fanOuts, QueryReconciliation queries,
            InboxReconciliation inbox, DeadLetterReprocessor deadLetters, IssueReview issueReview,
            Metrics metrics, Clock clock) {
        this.outbox = outbox;
        this.fanOuts = fanOuts;
        this.queries = queries;
        this.inbox = inbox;
        this.deadLetters = deadLetters;
        this.issueReview = issueReview;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public ReconcileResult apply(ReconcileCommand command) {
        int limit = command == null ? ReconcileCommand.DEFAULT_MAX_ITEMS : command.limit();
        Instant now = clock.instant();
        List<String> failed = new ArrayList<>();
        OutboxReconciliation.Report outboxReport = duty("outbox", () -> outbox.run(limit, now),
                new OutboxReconciliation.Report(0, 0, 0, 0, 0), failed);
        int resumed = duty("fan-out", () -> fanOuts.run(limit, now), 0, failed);
        QueryReconciliation.Report queryReport = duty("queries", () -> queries.run(limit, now),
                new QueryReconciliation.Report(0, 0, 0), failed);
        InboxReconciliation.Report inboxReport = duty("inbox", () -> inbox.run(limit, now),
                new InboxReconciliation.Report(0, 0, 0), failed);
        DeadLetterReprocessor.Report deadLetterReport = duty("dead letters", () -> deadLetters.run(now),
                new DeadLetterReprocessor.Report(0, 0), failed);
        int resolved = duty("issues", () -> issueReview.run(limit, now), 0, failed);
        metrics.reconcilerGauges(inboxReport.unmatched(), queryReport.unknown(), outboxReport.oldestAgeSeconds());
        ReconcileResult result = new ReconcileResult(outboxReport.pending(), outboxReport.republished(),
                outboxReport.failed(), outboxReport.stale(), resumed, queryReport.expired(), queryReport.concluded(),
                queryReport.unknown(), inboxReport.processed(), inboxReport.newlyUnmatched(),
                deadLetterReport.redriven(), deadLetterReport.discarded(), resolved, failed);
        LOGGER.info("Reconciler cycle: {}", result);
        return result;
    }

    private static <T> T duty(String name, Supplier<T> work, T fallback, List<String> failed) {
        try {
            return work.get();
        } catch (RuntimeException exception) {
            LOGGER.error("Reconciler duty '{}' failed, retried in the next cycle", name, exception);
            failed.add(name);
            return fallback;
        }
    }
}

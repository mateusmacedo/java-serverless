package com.mmda.serverless.quote.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Reconciliação agendada da quote (§9 do desenho): outbox pendente (M1, T19),
 * waiters parados (T13), versões
 * com a execução encerrada (T20), ocorrências e o ciclo de vida delas (M5),
 * resumo do {@code META},
 * {@code results-dlq} (T31) e {@code issues-dlq} (M5). WHY: um dever que falha
 * não impede os seguintes; a falha
 * fica no log e o próximo ciclo tenta de novo.
 */
@Singleton
@Named("reconciler")
public class ReconcilerHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOG = Logger.getLogger(ReconcilerHandler.class);

    private final OutboxReconciler outbox;
    private final WaiterReconciler waiters;
    private final VersionReconciler versions;
    private final DeadLetterReprocessor deadLetters;
    private final IssueReviewer issues;
    private final ReconcilerMetrics metrics;
    private final Clock clock;

    ReconcilerHandler(OutboxReconciler outbox, WaiterReconciler waiters, VersionReconciler versions,
            DeadLetterReprocessor deadLetters, IssueReviewer issues, ReconcilerMetrics metrics, Clock clock) {
        this.outbox = outbox;
        this.waiters = waiters;
        this.versions = versions;
        this.deadLetters = deadLetters;
        this.issues = issues;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        Instant now = Instant.now(clock);
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Integer> republished = run(report, "outbox", () -> outbox.republish(now));
        if (republished != null) {
            report.putAll(republished);
        }
        report.put("resumed", run(report, "resuming", () -> waiters.resumeStale(now)));
        report.put("versions", run(report, "versions", () -> versions.reconcile(now)));
        Integer expiredTasks = run(report, "expiredTasks", () -> waiters.reviewExpired(now));
        report.put("expiredTasks", expiredTasks);
        report.put("issues", run(report, "issues", () -> issues.review(now)));
        report.put("deadLetters", run(report, "deadLetters", () -> deadLetters.reprocess(now)));
        report.put("issueDeadLetters", run(report, "issueDeadLetters", () -> deadLetters.reprocessIssues(now)));
        Long outboxAge = run(report, "outboxAge", () -> outbox.oldestPendingAgeSeconds(now));
        report.put("outboxPendingAgeSeconds", outboxAge);
        metrics.emit(now, expiredTasks == null ? 0 : expiredTasks, outboxAge == null ? 0 : outboxAge);
        LOG.infof("reconciler report: %s", report);
        return report;
    }

    private static <T> T run(Map<String, Object> report, String duty, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException failure) {
            LOG.errorf(failure, "reconciler duty %s failed; the next cycle retries it", duty);
            report.put(duty + "Error", failure.getClass().getSimpleName());
            return null;
        }
    }
}

package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.QueryState;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolve as ocorrências abertas cujo problema sumiu (desenho, §9): a consulta
 * deixou de ser {@code UNKNOWN}, o
 * callback foi processado ou o evento foi publicado. WHY: a consulta
 * {@code UNKNOWN} também é concluída por um
 * callback tardio, e o outbox, pela relay, caminhos que não conhecem as
 * ocorrências.
 */
@Component
class IssueReview {

    private static final Logger LOGGER = LoggerFactory.getLogger(IssueReview.class);

    private final OperationIssues issues;
    private final QueryLedger ledger;
    private final CallbackLedger callbacks;
    private final OutboxStore outbox;

    IssueReview(OperationIssues issues, QueryLedger ledger, CallbackLedger callbacks, OutboxStore outbox) {
        this.issues = issues;
        this.ledger = ledger;
        this.callbacks = callbacks;
        this.outbox = outbox;
    }

    int run(int limit, Instant now) {
        int resolved = 0;
        for (OperationIssues.OpenIssue issue : issues.openSelfResolving(limit)) {
            try {
                String summary = clearedSummary(issue);
                if (summary == null) {
                    issues.deferReview(issue.issueId(), now);
                } else if (issues.resolve(issue.type(), issue.target(), summary)) {
                    resolved++;
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Issue review failed, retried in the next cycle: issueId={}", issue.issueId(), exception);
            }
        }
        return resolved;
    }

    /** Resumo da resolução, ou {@code null} quando o problema continua. */
    private String clearedSummary(OperationIssues.OpenIssue issue) {
        return switch (issue.type()) {
            case UNKNOWN_QUERY -> ledger.load(issue.target())
                    .filter(query -> query.state() == QueryState.UNKNOWN)
                    .isPresent() ? null : "A consulta foi concluída";
            case UNMATCHED_CALLBACK -> {
                int separator = issue.target().indexOf('#');
                boolean waiting = separator > 0 && callbacks.loadInbox(issue.target().substring(0, separator),
                        issue.target().substring(separator + 1))
                        .filter(CallbackLedger.InboxEntry::received)
                        .isPresent();
                yield waiting ? null : "O callback foi processado";
            }
            case STALE_OUTBOX -> outbox.isPending(issue.target()) ? null : "O evento foi publicado";
            case DEAD_LETTER -> null;
        };
    }
}

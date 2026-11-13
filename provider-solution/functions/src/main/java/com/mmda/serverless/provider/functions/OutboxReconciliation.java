package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.IssueType;
import com.mmda.serverless.provider.domain.operations.OperationIssue;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox pendente (desenho, §7 e §9): republica o que tem {@code gsi2sk} mais
 * antigo que
 * {@code outboxRepublishAfterSeconds}, desde o M1, e abre {@code STALE_OUTBOX}
 * para o item que continua pendente
 * com o {@code createdAt} além do limiar. A idade do mais antigo vira a métrica
 * {@code OutboxPendingAgeSeconds}.
 */
@Component
class OutboxReconciliation {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxReconciliation.class);

    private final OutboxStore outbox;
    private final OutboxPublisher publisher;
    private final OperationIssues issues;
    private final QueryLedger ledger;
    private final EventJson json;
    private final ProviderProperties properties;
    private final OperationsProperties operations;

    OutboxReconciliation(OutboxStore outbox, OutboxPublisher publisher, OperationIssues issues, QueryLedger ledger,
            EventJson json, ProviderProperties properties, OperationsProperties operations) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.issues = issues;
        this.ledger = ledger;
        this.json = json;
        this.properties = properties;
        this.operations = operations;
    }

    /**
     * @param oldestAgeSeconds idade, pelo {@code createdAt}, do item mais antigo
     *                         que continua pendente
     */
    record Report(int pending, int republished, int failed, int stale, long oldestAgeSeconds) {
    }

    Report run(int limit, Instant now) {
        String cutoff = Timestamps.format(now.minusSeconds(properties.outboxRepublishAfterSeconds()));
        int pending = 0;
        int republished = 0;
        int stale = 0;
        long oldestAge = 0;
        for (OutboxStore.PendingOutbox item : outbox.pending(limit)) {
            boolean published = false;
            if (item.gsi2sk().compareTo(cutoff) < 0) {
                pending++;
                published = item.entry() != null
                        && publisher.publish(item.entry()) == OutboxPublisher.Result.PUBLISHED;
                if (published) {
                    republished++;
                    LOGGER.info("Republished pending outbox item: eventId={} detailType={} createdAt={}",
                            item.eventId(), item.entry().detailType(), Timestamps.format(item.createdAt()));
                }
            }
            if (published) {
                continue;
            }
            Duration age = Duration.between(item.createdAt(), now);
            oldestAge = Math.max(oldestAge, age.toSeconds());
            if (age.compareTo(operations.staleOutboxAfter()) >= 0 && !isIssueEvent(item)
                    && issues.raise(observation(item, age), false)) {
                stale++;
            }
        }
        Report report = new Report(pending, republished, pending - republished, stale, oldestAge);
        if (pending > 0 || stale > 0) {
            LOGGER.warn("Outbox reconciliation: {}", report);
        }
        return report;
    }

    /**
     * Evento de ocorrência, que não abre {@code STALE_OUTBOX}. WHY: com o
     * barramento fora do ar, cada ocorrência
     * gravaria outro evento pendente, em cadeia; a idade dele já está no alarme
     * {@code OutboxPendingAgeSeconds}.
     */
    private static boolean isIssueEvent(OutboxStore.PendingOutbox item) {
        return item.entry() != null && (OperationIssue.RAISED.equals(item.entry().detailType())
                || OperationIssue.UPDATED.equals(item.entry().detailType()));
    }

    private OperationIssues.Observation observation(OutboxStore.PendingOutbox item, Duration age) {
        Map<?, ?> detail = item.entry() == null ? null : json.readOrNull(item.entry().detail(), Map.class);
        String queryId = text(detail, "queryId");
        String organizationId = queryId == null ? null : ledger.organizationOf(queryId).orElse(null);
        String summary = "Evento %s pendente de publicação há %d s".formatted(
                item.entry() == null ? "ilegível" : item.entry().detailType(), age.toSeconds());
        return new OperationIssues.Observation(IssueType.STALE_OUTBOX, item.eventId(), organizationId, queryId,
                text(detail, "quoteId"), summary);
    }

    private static String text(Map<?, ?> detail, String name) {
        Object value = detail == null ? null : detail.get(name);
        return value instanceof String string ? string : null;
    }
}

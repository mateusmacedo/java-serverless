package com.mmda.serverless.provider.functions;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Republicador agendado (M1): republica {@code OUTBOX_PENDING} mais antigo que
 * {@code outboxRepublishAfterSeconds}, com o mesmo código de publicação da relay. É o único caminho de
 * volta de um registro descartado pelo stream (ADR 0015); as tarefas de reconciliação do M4 entram aqui.
 */
@Component("reconciler")
class ReconcilerFunction implements Function<ReconcileCommand, ReconcileResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconcilerFunction.class);

    private final OutboxStore outbox;
    private final OutboxPublisher publisher;
    private final ProviderProperties properties;
    private final Clock clock;

    ReconcilerFunction(OutboxStore outbox, OutboxPublisher publisher, ProviderProperties properties, Clock clock) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ReconcileResult apply(ReconcileCommand command) {
        int limit = command == null ? ReconcileCommand.DEFAULT_MAX_ITEMS : command.limit();
        Instant cutoff = clock.instant().minusSeconds(properties.outboxRepublishAfterSeconds());
        List<OutboxEntry> pending = outbox.pendingOlderThan(cutoff, limit);
        int republished = 0;
        for (OutboxEntry entry : pending) {
            if (publisher.publish(entry) == OutboxPublisher.Result.PUBLISHED) {
                republished++;
            }
        }
        ReconcileResult result = new ReconcileResult(pending.size(), republished, pending.size() - republished);
        if (!pending.isEmpty()) {
            LOGGER.warn("Republished stale outbox items: {}", result);
        }
        return result;
    }
}

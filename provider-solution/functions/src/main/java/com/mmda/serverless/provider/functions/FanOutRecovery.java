package com.mmda.serverless.provider.functions;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fan-out que o líder do enriquecimento não terminou (pendência do M2, desenho,
 * §9): o líder concluiu, inclusive
 * com {@code FAILED}, e caiu antes de responder aos seguidores. O reconciler
 * refaz o fan-out pela cadeia gravada
 * na conclusão, sem nenhuma chamada externa, mesmo que outra cadeia já tenha
 * começado.
 */
@Component
class FanOutRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(FanOutRecovery.class);

    private final QueryLedger ledger;
    private final EnrichmentResolutions resolutions;
    private final ProviderProperties properties;
    private final ReuseProperties reuse;

    FanOutRecovery(QueryLedger ledger, EnrichmentResolutions resolutions, ProviderProperties properties,
            ReuseProperties reuse) {
        this.ledger = ledger;
        this.resolutions = resolutions;
        this.properties = properties;
        this.reuse = reuse;
    }

    /**
     * Só depois do tempo em que o próprio líder ainda pode estar no fan-out.
     * INVARIANT: só antes de a lápide do
     * {@code INFLIGHT#} e os {@code FOLLOWER#} vencerem; depois disso, uma cadeia
     * nova recomeça nas mesmas versões,
     * e o fan-out antigo alcançaria seguidores dela.
     */
    int run(int limit, Instant now) {
        Instant concludedBefore = now.minus(properties.workerTimeout()).minus(QueryRecord.CLOCK_SKEW);
        int resumed = 0;
        for (QueryLedger.PendingFanOut pending : ledger.pendingFanOuts(concludedBefore, limit)) {
            try {
                if (!now.isBefore(pending.concludedAt().plus(reuse.tombstone()))) {
                    LOGGER.error("Pending fan-out outlived the leadership tombstone and was dropped: queryId={}",
                            pending.queryId());
                    ledger.markFannedOut(pending.queryId(), now);
                    continue;
                }
                resolutions.resumeFanOut(pending);
                resumed++;
                LOGGER.warn("Fan-out of an interrupted leader resumed: queryId={} fencingVersion={}",
                        pending.queryId(), pending.term().fencingVersion());
            } catch (RuntimeException exception) {
                LOGGER.error("Fan-out recovery failed, retried in the next cycle: queryId={}", pending.queryId(),
                        exception);
            }
        }
        return resumed;
    }
}

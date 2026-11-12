package com.mmda.serverless.provider.domain.enrichment;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * O que um pedido de enriquecimento novo faz diante do índice de reuso e da liderança lidos com
 * {@code ConsistentRead} (desenho, §8): usar a evidência válida ({@code HIT}), esperar a consulta em voo
 * ({@code JOIN}) ou liderar uma consulta nova ({@code MISS}).
 */
public sealed interface ReusePlan {

    /**
     * @param reuse      entrada {@code REUSE#}, se existir
     * @param leadership item {@code INFLIGHT#}, se existir
     * @param workRef    pedido que decide
     */
    static ReusePlan of(Optional<ReuseIndexEntry> reuse, Optional<Leadership> leadership, String workRef,
                        Instant now, String policyVersion) {
        Objects.requireNonNull(workRef, "workRef");
        if (reuse.isPresent() && reuse.get().servesAt(now, policyVersion)) {
            return new Hit(reuse.get());
        }
        Long reuseRevision = reuse.map(ReuseIndexEntry::revision).orElse(null);
        if (leadership.isEmpty()) {
            return new Lead(1, 1, null, reuseRevision);
        }
        Leadership current = leadership.get();
        if (current.isLedBy(workRef)) {
            return new OwnLeadership();
        }
        if (current.isTransferableAt(now)) {
            return new Lead(current.fencingVersion() + 1, current.firstFencingVersionOfSuccessor(),
                    current.fencingVersion(), reuseRevision);
        }
        return new Join(current.fencingVersion());
    }

    record Hit(ReuseIndexEntry entry) implements ReusePlan {
    }

    /** Registro como seguidor da liderança em voo, na {@code fencingVersion} dela. */
    record Join(long fencingVersion) implements ReusePlan {
    }

    /**
     * Aquisição da liderança, condicionada ao estado lido: {@code expectedFencingVersion} e
     * {@code expectedReuseRevision} são {@code null} quando o item não existia.
     */
    record Lead(long fencingVersion, long firstFencingVersion, Long expectedFencingVersion,
                Long expectedReuseRevision) implements ReusePlan {
    }

    /**
     * A liderança é do próprio pedido: outra entrega dele assumiu primeiro. O pedido nunca se registra como
     * seguidor de si mesmo e segue a regra de reentrega da própria consulta.
     */
    record OwnLeadership() implements ReusePlan {
    }
}

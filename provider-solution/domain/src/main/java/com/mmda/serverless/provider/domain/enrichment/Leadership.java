package com.mmda.serverless.provider.domain.enrichment;

import java.time.Instant;
import java.util.Objects;

/**
 * Item {@code INFLIGHT#<queryKey>} (desenho, §5 e §8). Uma cadeia começa quando um pedido assume sem consulta
 * em voo e continua a cada lease vencida assumida por outro; {@code firstFencingVersion} é a primeira versão
 * dela. WHY: a conclusão responde só aos seguidores da própria cadeia, então o seguidor de uma cadeia já
 * concluída nunca recebe um segundo resultado. {@code evidenceId} e {@code resultType} só existem em
 * {@code COMPLETED}.
 */
public record Leadership(
        State state,
        long fencingVersion,
        long firstFencingVersion,
        String leaderWorkRef,
        String queryId,
        Instant leaseUntil,
        String evidenceId,
        EnrichmentResultType resultType) {

    public enum State {
        IN_FLIGHT,
        COMPLETED
    }

    public Leadership {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(leaderWorkRef, "leaderWorkRef");
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (firstFencingVersion < 1 || firstFencingVersion > fencingVersion) {
            throw new IllegalArgumentException("firstFencingVersion must be between 1 and fencingVersion");
        }
        if (state == State.COMPLETED && (evidenceId == null || resultType == null)) {
            throw new IllegalArgumentException("A completed leadership records its evidence and result");
        }
    }

    /**
     * Condição de aquisição do §8: {@code state = COMPLETED OR leaseUntil < :agora}. A lease ainda vale no
     * próprio instante {@code leaseUntil}.
     */
    public boolean isTransferableAt(Instant now) {
        return state == State.COMPLETED || leaseUntil.isBefore(now);
    }

    /**
     * Primeira versão da cadeia de quem assume esta liderança: assumir uma lease vencida continua a cadeia, e
     * os seguidores dela passam a esperar o novo líder; assumir depois de uma conclusão começa outra.
     */
    public long firstFencingVersionOfSuccessor() {
        return state == State.COMPLETED ? fencingVersion + 1 : firstFencingVersion;
    }

    public boolean chainIncludes(long version) {
        return version >= firstFencingVersion && version <= fencingVersion;
    }

    /** A conclusão desta liderança já respondeu ao seguidor registrado em {@code followerVersion}. */
    public boolean answers(long followerVersion) {
        return state == State.COMPLETED && chainIncludes(followerVersion);
    }

    public boolean isLedBy(String workRef) {
        return leaderWorkRef.equals(workRef);
    }
}

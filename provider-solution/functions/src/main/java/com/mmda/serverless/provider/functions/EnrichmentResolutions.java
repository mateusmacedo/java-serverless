package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentProfile;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentRequest;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResolved;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResultType;
import com.mmda.serverless.provider.domain.enrichment.Leadership;
import com.mmda.serverless.provider.domain.enrichment.ReuseDecision;
import com.mmda.serverless.provider.domain.enrichment.ReuseIndexEntry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * O {@code EnrichmentResolved} de cada pedido (desenho, §8): a conclusão do líder, com o índice de reuso e o
 * fan-out para os seguidores, o {@code HIT} e o {@code JOIN} de quem encontra a liderança já concluída.
 */
@Component
class EnrichmentResolutions {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnrichmentResolutions.class);
    private static final int MAX_REUSE_ROUNDS = 3;

    private final QueryLedger ledger;
    private final ReuseLedger reuseLedger;
    private final EventJson json;
    private final ReuseProperties reuse;
    private final Clock clock;

    EnrichmentResolutions(QueryLedger ledger, ReuseLedger reuseLedger, EventJson json, ReuseProperties reuse,
                          Clock clock) {
        this.ledger = ledger;
        this.reuseLedger = reuseLedger;
        this.json = json;
        this.reuse = reuse;
        this.clock = clock;
    }

    /**
     * Conclui a consulta com o outbox próprio ({@code MISS}); {@code newQuery} é a consulta criada já concluída,
     * sem chamada. Com {@code term}, conclui também a liderança e responde aos seguidores; com a lease
     * transferida, grava só a própria evidência e o próprio resultado, sem tocar índice nem liderança (T15).
     */
    void settle(EnrichmentRequest request, String queryId, QueryDraft newQuery, QueryState state,
                Settlement.AttemptResult attempt, EvidenceDraft evidence, EnrichmentResolved.Outcome outcome,
                LeaderTerm term, Instant now) {
        settle(EnrichmentResolved.Recipient.of(request), queryId, newQuery, state, attempt, evidence, outcome, term,
                now);
    }

    /**
     * A mesma conclusão para o pedido de origem da consulta, sem a mensagem do pedido em mãos, como a do reconciler
     * (§9). O pedido é deduplicado pelo {@code workRef}, então o {@code CONSUMED} é o mesmo.
     */
    void settle(EnrichmentResolved.Recipient request, String queryId, QueryDraft newQuery, QueryState state,
                Settlement.AttemptResult attempt, EvidenceDraft evidence, EnrichmentResolved.Outcome outcome,
                LeaderTerm term, Instant now) {
        String policyVersion = evidence.policyVersion() == null ? reuse.policyVersion() : evidence.policyVersion();
        EnrichmentResolved event = EnrichmentResolved.of(request, queryId, evidence.evidenceId(), ReuseDecision.MISS,
                policyVersion, outcome, Timestamps.format(now));
        Settlement settlement = new Settlement(queryId, newQuery, state, attempt, evidence, outboxEvent(event),
                consumedId(request.workRef()), request.workRef());
        if (term == null) {
            settleAlone(settlement, request, outcome, now);
            return;
        }
        for (int round = 1; ; round++) {
            ReuseWrite write = reuseWrite(term, queryId, evidence.evidenceId(), outcome, policyVersion);
            QueryLedger.LeaderSettlement settledAs = ledger.settleAsLeader(settlement, term, write.entry(),
                    write.expectedRevision(), now);
            switch (settledAs) {
                case SETTLED, SETTLED_ALREADY_ANSWERED -> {
                    LOGGER.info("Enrichment resolved by the leader: queryId={} quoteId={} fencingVersion={} "
                                    + "resultType={} state={} settlement={}", queryId, request.quoteId(),
                            term.fencingVersion(), outcome.resultType(), state, settledAs);
                    fanOut(term, request.workRef(), queryId, evidence.evidenceId(), policyVersion, outcome);
                    return;
                }
                case FENCED -> {
                    LOGGER.warn("Leadership was transferred before the conclusion; only the own evidence is kept: "
                            + "queryId={} fencingVersion={}", queryId, term.fencingVersion());
                    settleAlone(settlement, request, outcome, now);
                    return;
                }
                case ALREADY_SETTLED -> {
                    LOGGER.warn("Enrichment query was settled by another delivery: queryId={}", queryId);
                    return;
                }
                case REUSE_CHANGED -> {
                    if (round >= MAX_REUSE_ROUNDS) {
                        throw new IllegalStateException("The reuse index kept changing: queryId=" + queryId);
                    }
                }
            }
        }
    }

    /**
     * Consulta interrompida (§8), pela reentrega do pedido ou pelo reconciler, sem nunca repetir a chamada: fica
     * {@code UNKNOWN} com tentativa aberta, {@code FAILED} sem ela, e a liderança conclui com {@code FAILED} e
     * responde aos seguidores só se ainda for dela.
     */
    void settleInterrupted(EnrichmentResolved.Recipient recipient, QueryRecord query, Instant now) {
        boolean openAttempt = query.hasOpenAttempt();
        String errorClass = openAttempt ? ErrorClasses.INTERRUPTED_ATTEMPT : ErrorClasses.INTERRUPTED_BEFORE_CALL;
        EvidenceDraft evidence = new EvidenceDraft(Identities.evidenceId(query.queryId(), query.attemptCount()),
                EnrichmentResultType.FAILED.name(), null, null, null, Timestamps.format(now), null, null, errorClass,
                query.fencingVersion(), reuse.policyVersion());
        settle(recipient, query.queryId(), null, openAttempt ? QueryState.UNKNOWN : QueryState.FAILED, null, evidence,
                EnrichmentResolved.Outcome.failed(errorClass), interruptedTerm(query), now);
    }

    /**
     * A liderança da consulta interrompida, se a {@code fencingVersion} e o {@code queryId} do {@code INFLIGHT#}
     * ainda forem os dela.
     */
    private LeaderTerm interruptedTerm(QueryRecord query) {
        if (!query.isLeader()) {
            return null;
        }
        return reuseLedger.leadership(query.queryKey())
                .filter(leadership -> leadership.state() == Leadership.State.IN_FLIGHT
                        && leadership.fencingVersion() == query.fencingVersion()
                        && leadership.queryId().equals(query.queryId()))
                .map(leadership -> new LeaderTerm(query.queryKey(), leadership.fencingVersion(),
                        leadership.firstFencingVersion()))
                .orElse(null);
    }

    /** {@code HIT}: o resultado da evidência válida do índice, sem chamada externa. */
    void publishHit(EnrichmentRequest request, ReuseIndexEntry entry) {
        publish(request, evidence(entry.queryId(), entry.evidenceId()), ReuseDecision.HIT);
    }

    /** {@code JOIN} publicado pelo próprio seguidor, quando encontra concluída a liderança em que se registrou. */
    void publishRecorded(EnrichmentRequest request, Leadership leadership) {
        publish(request, evidence(leadership.queryId(), leadership.evidenceId()), ReuseDecision.JOIN);
    }

    /**
     * Reentrega do líder que encontra o {@code INFLIGHT#} concluído com a própria consulta: refaz o fan-out,
     * que é idempotente pelo {@code eventId} (§8).
     */
    void redoFanOut(QueryRecord query, String leaderWorkRef) {
        Optional<Leadership> leadership = reuseLedger.leadership(query.queryKey());
        if (leadership.isEmpty() || leadership.get().state() != Leadership.State.COMPLETED
                || !leadership.get().queryId().equals(query.queryId())) {
            return;
        }
        Leadership completed = leadership.get();
        StoredEvidence evidence = evidence(completed.queryId(), completed.evidenceId());
        fanOut(new LeaderTerm(query.queryKey(), completed.fencingVersion(), completed.firstFencingVersion()),
                leaderWorkRef, completed.queryId(), completed.evidenceId(), policyVersion(evidence),
                outcomeOf(evidence));
    }

    /**
     * Fora da transação do líder, que aceita até 100 ações: um outbox e um {@code CONSUMED} por seguidor da
     * cadeia, com {@code eventId} determinístico e {@code attribute_not_exists} (ADR 0015).
     */
    private void fanOut(LeaderTerm term, String leaderWorkRef, String queryId, String evidenceId,
                        String policyVersion, EnrichmentResolved.Outcome outcome) {
        List<ReuseLedger.Follower> followers = reuseLedger.followers(term.queryKey(), term.firstFencingVersion(),
                term.fencingVersion());
        int published = 0;
        for (ReuseLedger.Follower follower : followers) {
            if (follower.workRef().equals(leaderWorkRef)) {
                continue;
            }
            Instant now = clock.instant();
            EnrichmentResolved event = EnrichmentResolved.of(
                    new EnrichmentResolved.Recipient(follower.workRef(), follower.quoteId(), follower.version()),
                    queryId, evidenceId, ReuseDecision.JOIN, policyVersion, outcome, Timestamps.format(now));
            if (ledger.resolve(outboxEvent(event), consumedId(follower.workRef()), follower.workRef(), now)) {
                published++;
            }
        }
        if (!followers.isEmpty()) {
            LOGGER.info("Enrichment fan-out: queryId={} followers={} published={}", queryId, followers.size(),
                    published);
        }
        ledger.markFannedOut(queryId, clock.instant());
    }

    /**
     * Fan-out que o líder não terminou (pendência do M2, §9): refeito pela cadeia gravada na conclusão, mesmo que
     * outra cadeia já tenha começado no {@code INFLIGHT#}. O {@code CONSUMED} de cada seguidor impede uma segunda
     * resposta.
     */
    void resumeFanOut(QueryLedger.PendingFanOut pending) {
        StoredEvidence evidence = evidence(pending.queryId(), pending.evidenceId());
        fanOut(pending.term(), pending.leaderWorkRef(), pending.queryId(), pending.evidenceId(),
                policyVersion(evidence), outcomeOf(evidence));
    }

    private void settleAlone(Settlement settlement, EnrichmentResolved.Recipient request,
                             EnrichmentResolved.Outcome outcome, Instant now) {
        if (ledger.settle(settlement, now)) {
            LOGGER.info("Enrichment resolved: queryId={} quoteId={} version={} resultType={} state={}",
                    settlement.queryId(), request.quoteId(), request.version(), outcome.resultType(),
                    settlement.finalState());
        } else {
            LOGGER.warn("Enrichment query was settled by another delivery: queryId={}", settlement.queryId());
        }
    }

    private void publish(EnrichmentRequest request, StoredEvidence evidence, ReuseDecision decision) {
        Instant now = clock.instant();
        EnrichmentResolved event = EnrichmentResolved.of(request, evidence.queryId(), evidence.evidenceId(), decision,
                policyVersion(evidence), outcomeOf(evidence), Timestamps.format(now));
        if (ledger.resolve(outboxEvent(event), consumedId(request.workRef()), request.workRef(), now)) {
            LOGGER.info("Enrichment resolved by reuse: reuseDecision={} queryId={} quoteId={} version={}", decision,
                    evidence.queryId(), request.quoteId(), request.version());
        } else {
            LOGGER.info("Enrichment request already resolved: workRef={}", request.workRef());
        }
    }

    private ReuseWrite reuseWrite(LeaderTerm term, String queryId, String evidenceId,
                                  EnrichmentResolved.Outcome outcome, String policyVersion) {
        if (!outcome.resultType().isReusable()) {
            return new ReuseWrite(null, null);
        }
        Long expected = reuseLedger.reuseEntry(term.queryKey()).map(ReuseIndexEntry::revision).orElse(null);
        return new ReuseWrite(new ReuseIndexEntry(queryId, evidenceId, outcome.resultType(),
                Timestamps.parse(outcome.reusableUntil()), expected == null ? 1 : expected + 1, policyVersion),
                expected);
    }

    private StoredEvidence evidence(String queryId, String evidenceId) {
        return ledger.loadEvidence(queryId, evidenceId).orElseThrow(() -> new IllegalStateException(
                "Evidence %s of query %s is missing".formatted(evidenceId, queryId)));
    }

    private String policyVersion(StoredEvidence evidence) {
        return evidence.policyVersion() == null ? reuse.policyVersion() : evidence.policyVersion();
    }

    private EnrichmentResolved.Outcome outcomeOf(StoredEvidence evidence) {
        return switch (EnrichmentResultType.valueOf(evidence.resultType())) {
            case FOUND -> EnrichmentResolved.Outcome.found(read(evidence, EnrichmentProfile.class),
                    evidence.reusableUntil());
            case NOT_FOUND -> EnrichmentResolved.Outcome.notFound(read(evidence, NotFoundResult.class).notFoundRefs(),
                    evidence.reusableUntil());
            case FAILED -> EnrichmentResolved.Outcome.failed(evidence.errorClass());
        };
    }

    private <T> T read(StoredEvidence evidence, Class<T> type) {
        T value = json.readOrNull(evidence.normalizedResult(), type);
        if (value == null) {
            throw new IllegalStateException("Evidence " + evidence.evidenceId() + " has no readable result");
        }
        return value;
    }

    /**
     * Chave do {@code CONSUMED} de um pedido: o {@code eventId} que o contrato da quote atribui ao
     * {@code workRef}. WHY: o fan-out só conhece o {@code workRef} do seguidor, e a deduplicação é pelo
     * {@code workRef}, não pelo {@code eventId} recebido.
     */
    static String consumedId(String workRef) {
        return EventIds.request(workRef);
    }

    private static Settlement.OutboxEvent outboxEvent(EnrichmentResolved event) {
        return new Settlement.OutboxEvent(event.eventId(), EnrichmentResolved.DETAIL_TYPE, event);
    }

    /** {@code normalizedResult} de uma evidência {@code NOT_FOUND}. */
    record NotFoundResult(List<String> notFoundRefs) {
    }

    private record ReuseWrite(ReuseIndexEntry entry, Long expectedRevision) {
    }
}

package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.Digests;
import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentResolved;
import com.mmda.serverless.provider.domain.insurer.InsurerResultRecorded;
import com.mmda.serverless.provider.domain.insurer.InsurerResultStatus;
import com.mmda.serverless.provider.domain.insurer.InsurerStatus;
import com.mmda.serverless.provider.domain.operations.IssueType;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reconciliação de uma consulta, comum ao {@code reconciler} e ao
 * {@code reconcileWorker} (desenho, §8 e §9). A
 * consulta em voo além do {@code deadlineAt}, sem entrega viva, é tratada como
 * interrompida: fica {@code UNKNOWN}
 * se o pedido pode ter chegado ao fornecedor. A {@code UNKNOWN} de seguradora
 * com API de situação é consultada e
 * concluída com o resultado dela; sem API de situação, abre
 * {@code UNKNOWN_QUERY}. INVARIANT: nada aqui repete a
 * chamada ao fornecedor; lease ou prazo vencidos só autorizam ler o estado.
 */
@Component
class QueryReconciliation {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryReconciliation.class);
    private static final Set<QueryState> SETTLEABLE = Set.of(QueryState.IN_FLIGHT, QueryState.UNKNOWN);

    private final QueryLedger ledger;
    private final PendingCallbacks pendingCallbacks;
    private final EnrichmentResolutions resolutions;
    private final ProviderClients clients;
    private final RawResponses rawResponses;
    private final OperationIssues issues;
    private final EventJson json;
    private final ProviderProperties properties;
    private final OperationsProperties operations;
    private final Clock clock;

    QueryReconciliation(QueryLedger ledger, PendingCallbacks pendingCallbacks, EnrichmentResolutions resolutions,
            ProviderClients clients,
            RawResponses rawResponses, OperationIssues issues, EventJson json,
            ProviderProperties properties, OperationsProperties operations, Clock clock) {
        this.ledger = ledger;
        this.pendingCallbacks = pendingCallbacks;
        this.resolutions = resolutions;
        this.clients = clients;
        this.rawResponses = rawResponses;
        this.issues = issues;
        this.json = json;
        this.properties = properties;
        this.operations = operations;
        this.clock = clock;
    }

    enum Outcome {
        NOT_FOUND,
        /** Já estava concluída antes desta reconciliação. */
        SETTLED,
        /** Em voo dentro do prazo, ou com uma entrega que pode estar viva. */
        IN_PROGRESS,
        /** Concluída agora, pelo prazo ou pela API de situação. */
        CONCLUDED,
        /** Continua {@code UNKNOWN}. */
        UNKNOWN
    }

    record Result(Outcome outcome, String safeSummary) {
    }

    /**
     * Ciclo do reconciler: as consultas em voo além do prazo e as {@code UNKNOWN}.
     */
    record Report(int expired, int concluded, int unknown) {
    }

    Report run(int limit, Instant now) {
        StatusReads reads = StatusReads.until(clock, clock.instant().plus(operations.statusReadBudget()));
        Set<String> handled = new HashSet<>();
        int expired = 0;
        int concluded = 0;
        int unknown = 0;
        for (String queryId : ledger.indexedQueries(Items.QUERY_IN_FLIGHT, now, limit)) {
            handled.add(queryId);
            Outcome outcome = guarded(queryId, reads);
            if (outcome != Outcome.IN_PROGRESS) {
                expired++;
            }
            concluded += outcome == Outcome.CONCLUDED ? 1 : 0;
            unknown += outcome == Outcome.UNKNOWN ? 1 : 0;
        }
        for (String queryId : ledger.indexedQueries(Items.QUERY_UNKNOWN, now.plusSeconds(1), limit)) {
            if (handled.add(queryId)) {
                Outcome outcome = guarded(queryId, reads);
                concluded += outcome == Outcome.CONCLUDED ? 1 : 0;
                unknown += outcome == Outcome.UNKNOWN ? 1 : 0;
            }
        }
        return new Report(expired, concluded, unknown);
    }

    Result reconcile(String queryId) {
        return reconcile(queryId, StatusReads.unlimited());
    }

    Result reconcile(QueryRecord query) {
        return reconcile(query, StatusReads.unlimited());
    }

    private Result reconcile(String queryId, StatusReads reads) {
        return ledger.load(queryId)
                .map(query -> reconcile(query, reads))
                .orElseGet(() -> new Result(Outcome.NOT_FOUND, "Consulta não encontrada"));
    }

    private Result reconcile(QueryRecord query, StatusReads reads) {
        Instant now = clock.instant();
        return switch (query.state()) {
            case COMPLETED, FAILED -> new Result(Outcome.SETTLED, "A consulta já estava concluída");
            case IN_FLIGHT -> expired(query, now, reads);
            case UNKNOWN -> unknown(query, reads);
        };
    }

    private Outcome guarded(String queryId, StatusReads reads) {
        try {
            return reconcile(queryId, reads).outcome();
        } catch (RuntimeException exception) {
            LOGGER.error("Query reconciliation failed, retried in the next cycle: queryId={}", queryId, exception);
            return Outcome.IN_PROGRESS;
        }
    }

    private Result expired(QueryRecord query, Instant now, StatusReads reads) {
        Optional<Instant> busyUntil = query.busyUntil(properties.workerTimeout());
        if (!query.pastDeadline(now) || busyUntil.map(now::isBefore).orElse(false)) {
            return new Result(Outcome.IN_PROGRESS, "A consulta ainda está em andamento");
        }
        if (query.workRef() == null || query.quoteId() == null) {
            LOGGER.error("Query without its request origin cannot be reconciled: queryId={}", query.queryId());
            return new Result(Outcome.IN_PROGRESS, "A consulta não tem a origem do pedido");
        }
        if (!query.isInsurer()) {
            resolutions.settleInterrupted(new EnrichmentResolved.Recipient(query.workRef(), query.quoteId(),
                    query.version() == null ? 0 : query.version()), query, now);
            return afterExpiry(query.queryId(), reads);
        }
        if (query.providerRequestId() != null || query.hasOpenAttempt()) {
            ledger.settle(new Settlement(query.queryId(), null, QueryState.UNKNOWN, null, null, null, null,
                    query.workRef()), now);
            LOGGER.warn("Insurer query past its deadline left UNKNOWN: queryId={} insurerId={}", query.queryId(),
                    query.insurerId());
            if (query.providerRequestId() != null) {
                // WHY: a entrega que gravou o 202 pode ter caído antes da metade do worker no
                // CORR#; refeita aqui, ela
                // conclui com o callback já guardado na inbox antes de qualquer leitura da
                // situação (§8)
                pendingCallbacks.meet(query.insurerId(), query.providerRequestId(), query.queryId());
            }
            return afterExpiry(query.queryId(), reads);
        }
        if (!concludeUnavailable(query, now)) {
            return new Result(Outcome.IN_PROGRESS, "Outra entrega mudou a consulta durante a reconciliação");
        }
        return new Result(Outcome.CONCLUDED, "Consulta concluída sem resposta dentro do prazo");
    }

    private Result afterExpiry(String queryId, StatusReads reads) {
        QueryRecord settled = ledger.load(queryId).orElseThrow();
        if (settled.state() == QueryState.UNKNOWN) {
            return unknown(settled, reads);
        }
        return new Result(Outcome.CONCLUDED, "Consulta concluída sem resposta dentro do prazo");
    }

    private Result unknown(QueryRecord query, StatusReads reads) {
        if (!query.isInsurer() || !operations.statusInsurers().contains(query.insurerId())) {
            raiseUnknown(query, "Consulta sem resposta do fornecedor, que não tem API de situação");
            ledger.leaveUnknownIndex(query.queryId());
            return new Result(Outcome.UNKNOWN, "O fornecedor não tem API de situação; a consulta continua UNKNOWN");
        }
        if (!reads.allowed(query.insurerId())) {
            ledger.deferUnknown(query.queryId(), clock.instant());
            return new Result(Outcome.UNKNOWN, "Leitura da situação adiada para o próximo ciclo");
        }
        String reference = query.providerRequestId() != null ? query.providerRequestId() : query.queryId();
        ProviderExchange<InsurerStatus> exchange = clients.insurerStatus(query.insurerId(), reference);
        Result result = switch (InsurerStatus.classify(exchange.outcome())) {
            case InsurerStatus.Completed completed -> concludeFromStatus(query, completed, exchange.rawBody());
            case InsurerStatus.Pending ignored ->
                new Result(Outcome.UNKNOWN, "A seguradora ainda processa o pedido");
            case InsurerStatus.Unrecognized ignored -> {
                raiseUnknown(query, "A seguradora não reconhece o pedido na API de situação");
                yield new Result(Outcome.UNKNOWN, "A seguradora não reconhece o pedido");
            }
            case InsurerStatus.Unreachable unreachable -> {
                reads.unreachable(query.insurerId());
                LOGGER.warn("Insurer status unavailable, retried in the next cycle: queryId={} errorClass={}",
                        query.queryId(), unreachable.errorClass());
                yield new Result(Outcome.UNKNOWN, "API de situação indisponível");
            }
        };
        meetCallbacks(query, exchange);
        if (result.outcome() == Outcome.UNKNOWN) {
            ledger.deferUnknown(query.queryId(), clock.instant());
        }
        return result;
    }

    /**
     * Conclui a consulta com o resultado da API de situação, como o callback que
     * faltava (§8): evidência, tentativa,
     * {@code InsurerResultRecorded} com o {@code eventId} UUIDv5 do {@code queryId}
     * e o {@code CONSUMED} do pedido.
     */
    private Result concludeFromStatus(QueryRecord query, InsurerStatus.Completed completed, String rawBody) {
        Instant now = clock.instant();
        String queryId = query.queryId();
        Optional<QueryRecord.Attempt> attempt = query.callbackAttempt(query.providerRequestId());
        int sequence = attempt.map(QueryRecord.Attempt::sequence).orElse(0);
        String rawResponseRef = rawBody == null ? null : rawResponses.storeStatus(queryId, sequence, rawBody);
        InsurerResultRecorded.Outcome outcome = InsurerResultRecorded.Outcome.of(completed.verdict(), rawResponseRef);
        boolean unavailable = outcome.status() == InsurerResultStatus.UNAVAILABLE;
        String errorClass = unavailable ? outcome.reasonCode() : null;
        String receivedAt = Timestamps.format(now);
        EvidenceDraft evidence = new EvidenceDraft(Identities.evidenceId(queryId, sequence), outcome.status().name(),
                json.write(outcome.evidenceBody()), rawResponseRef, observedAt(completed.completedAt(), receivedAt),
                receivedAt, null, rawBody == null ? null : Digests.sha256Hex(rawBody), errorClass);
        Settlement.AttemptResult finished = attempt.filter(open -> open.finishedAt() == null)
                .map(open -> new Settlement.AttemptResult(open.sequence(), outcome.status().name(), null, errorClass,
                        query.providerRequestId()))
                .orElse(null);
        InsurerResultRecorded event = InsurerResultRecorded.of(query.origin(), queryId,
                attempt.isPresent() ? Identities.attemptRef(queryId, sequence) : null, outcome, receivedAt);
        Settlement settlement = new Settlement(queryId, null, unavailable ? QueryState.FAILED : QueryState.COMPLETED,
                finished, evidence,
                new Settlement.OutboxEvent(event.eventId(), InsurerResultRecorded.DETAIL_TYPE, event),
                consumedId(query), query.workRef());
        if (!ledger.settleFrom(settlement, SETTLEABLE, now)) {
            return new Result(Outcome.SETTLED, "A consulta foi concluída por outro caminho");
        }
        LOGGER.warn("UNKNOWN insurer query concluded by the status API, without a new call: queryId={} insurerId={} "
                + "status={}", queryId, query.insurerId(), outcome.status());
        issues.resolve(IssueType.UNKNOWN_QUERY, queryId, "Consulta concluída pela API de situação da seguradora");
        return new Result(Outcome.CONCLUDED, "Consulta concluída pela API de situação da seguradora");
    }

    /**
     * Grava a metade do worker no {@code CORR#} com o {@code providerRequestId} da
     * consulta ou, quando o {@code 202}
     * se perdeu, o da API de situação. WHY: o callback que chegou só com o
     * {@code providerRequestId} fica retido na
     * inbox até esse encontro; com a consulta já concluída, ele vira evidência
     * tardia, não {@code UNMATCHED} (§8).
     */
    private void meetCallbacks(QueryRecord query, ProviderExchange<InsurerStatus> exchange) {
        String providerRequestId = query.providerRequestId();
        if (providerRequestId == null && exchange.outcome() instanceof CallOutcome.Responded<InsurerStatus> responded
                && responded.reply() != null) {
            providerRequestId = responded.reply().providerRequestId();
        }
        if (providerRequestId != null) {
            pendingCallbacks.meet(query.insurerId(), providerRequestId, query.queryId());
        }
    }

    /**
     * Em voo sem tentativa aberta e sem resposta, além do prazo:
     * {@code UNAVAILABLE} sem chamada (§9).
     */
    private boolean concludeUnavailable(QueryRecord query, Instant now) {
        String reasonCode = ErrorClasses.DEADLINE_EXCEEDED_BEFORE_CALL;
        int sequence = query.attemptCount();
        String receivedAt = Timestamps.format(now);
        EvidenceDraft evidence = new EvidenceDraft(Identities.evidenceId(query.queryId(), sequence), "UNAVAILABLE",
                json.write(Map.of("reasonCode", reasonCode)), null, null, receivedAt, null, null, reasonCode);
        InsurerResultRecorded event = InsurerResultRecorded.of(query.origin(), query.queryId(),
                sequence == 0 ? null : Identities.attemptRef(query.queryId(), sequence),
                InsurerResultRecorded.Outcome.unavailable(reasonCode), receivedAt);
        return ledger.settleWithoutNewAttempt(new Settlement(query.queryId(), null, QueryState.FAILED, null,
                evidence, new Settlement.OutboxEvent(event.eventId(), InsurerResultRecorded.DETAIL_TYPE, event),
                consumedId(query), query.workRef()), sequence, now);
    }

    private void raiseUnknown(QueryRecord query, String summary) {
        issues.raise(new OperationIssues.Observation(IssueType.UNKNOWN_QUERY, query.queryId(),
                query.organizationId(), query.queryId(), query.quoteId(), summary), false);
    }

    private static String consumedId(QueryRecord query) {
        return query.requestEventId() != null ? query.requestEventId() : EventIds.request(query.workRef());
    }

    private static String observedAt(String completedAt, String fallback) {
        if (completedAt == null) {
            return fallback;
        }
        try {
            return Timestamps.format(Timestamps.parse(completedAt));
        } catch (DateTimeParseException exception) {
            return fallback;
        }
    }

    /**
     * Leituras da API de situação num ciclo do {@code reconciler}. WHY: as leituras
     * são em série, cada uma com o
     * timeout HTTP; sem teto, uma seguradora lenta ou fora do ar consumiria o
     * timeout do reconciler antes da inbox,
     * das DLQs e das métricas. Depois do teto, ou da primeira leitura sem resposta
     * de um provedor, as consultas
     * seguem {@code UNKNOWN} para o próximo ciclo.
     */
    static final class StatusReads {

        private final Clock clock;
        private final Instant until;
        private final Set<String> unreachable = new HashSet<>();

        private StatusReads(Clock clock, Instant until) {
            this.clock = clock;
            this.until = until;
        }

        static StatusReads unlimited() {
            return new StatusReads(null, null);
        }

        static StatusReads until(Clock clock, Instant until) {
            return new StatusReads(clock, until);
        }

        boolean allowed(String provider) {
            return !unreachable.contains(provider) && (until == null || clock.instant().isBefore(until));
        }

        void unreachable(String provider) {
            unreachable.add(provider);
        }
    }
}

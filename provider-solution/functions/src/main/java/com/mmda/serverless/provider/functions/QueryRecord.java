package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.QueryState;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.insurer.InsurerResultRecorded;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Consulta lida com {@code ConsistentRead}, com as tentativas, para decidir a
 * reentrega (§8).
 * {@code queryKey} só existe no enriquecimento e {@code fencingVersion}, só na
 * consulta de um líder. Os campos
 * do pedido de origem ({@code workRef} a {@code requestEventId}) permitem
 * gravar o resultado de um callback, e
 * {@code operation}, {@code organizationId} e {@code deadlineAt}, reconciliar a
 * consulta sem o pedido (M4).
 */
record QueryRecord(String queryId, QueryState state, String providerRequestId, String queryKey, Long fencingVersion,
        List<Attempt> attempts, String provider, String workRef, String quoteId, Integer version,
        String insurerId, String requestEventId, String operation, String organizationId,
        Instant deadlineAt) {

    /**
     * Folga para diferença de relógio entre as instâncias que gravaram e leram as
     * tentativas.
     */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    QueryRecord {
        attempts = List.copyOf(attempts);
    }

    /**
     * A consulta liderou a {@code queryKey}; sem {@code fencingVersion}, como no
     * M1, não há liderança.
     */
    boolean isLeader() {
        return fencingVersion != null && queryKey != null;
    }

    /**
     * Tentativa gravada antes do HTTP e nunca concluída: o pedido pode ter chegado
     * ao fornecedor.
     */
    boolean hasOpenAttempt() {
        return attempts.stream().anyMatch(attempt -> attempt.finishedAt() == null);
    }

    /**
     * Até quando a entrega que gravou a última tentativa pode estar viva.
     * INVARIANT: a invocação começou antes
     * do {@code startedAt} (ou do {@code finishedAt}) que gravou e termina, no
     * máximo, no timeout da função; antes
     * desse instante, a tentativa aberta ou o backoff em curso podem ser de uma
     * entrega duplicada viva, não de
     * uma interrompida.
     */
    Optional<Instant> busyUntil(Duration workerTimeout) {
        return attempts.stream()
                .flatMap(attempt -> Stream.of(attempt.startedAt(), attempt.finishedAt()))
                .filter(Objects::nonNull)
                .map(Timestamps::parse)
                .max(Comparator.naturalOrder())
                .map(last -> last.plus(workerTimeout).plus(CLOCK_SKEW));
    }

    int attemptCount() {
        return attempts.size();
    }

    /**
     * Tentativa que o callback conclui: a que recebeu o {@code 202} com o mesmo
     * {@code providerRequestId}; sem
     * ela, como no callback que chega antes da gravação do {@code 202}, a última
     * ainda aberta.
     */
    Optional<Attempt> callbackAttempt(String callbackProviderRequestId) {
        return attempts.stream()
                .filter(attempt -> attempt.providerRequestId() != null
                        && attempt.providerRequestId().equals(callbackProviderRequestId))
                .findFirst()
                .or(() -> attempts.stream().filter(attempt -> attempt.finishedAt() == null)
                        .reduce((first, last) -> last))
                .or(() -> attempts.stream().reduce((first, last) -> last));
    }

    boolean isInsurer() {
        return QueryDraft.QUOTE.equals(operation);
    }

    /**
     * O prazo do pedido passou: o resultado que chegar agora é evidência tardia
     * para a quote.
     */
    boolean pastDeadline(Instant now) {
        return deadlineAt != null && !now.isBefore(deadlineAt);
    }

    InsurerResultRecorded.Origin origin() {
        return new InsurerResultRecorded.Origin(workRef, quoteId, version == null ? 0 : version, insurerId);
    }

    record Attempt(int sequence, String startedAt, String finishedAt, String outcome, String providerRequestId) {
    }
}

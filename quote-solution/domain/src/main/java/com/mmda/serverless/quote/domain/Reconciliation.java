package com.mmda.serverless.quote.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Decisões do {@code reconciler} da quote (§9 do desenho, "Reconciliação (M4)"), sem acesso à AWS: o que
 * fazer com uma versão ativa, quando uma tarefa expirada abre ocorrência e quando uma mensagem da DLQ volta à
 * fila de origem.
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    /** Situação da execução da versão, lida com {@code DescribeExecution}. */
    public enum ExecutionState {
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        ABORTED,
        /** Execução inexistente: o {@code StartQuoteRequested} ainda não foi publicado ou se perdeu. */
        MISSING;

        /** Estado de um {@code status} do Step Functions; um valor desconhecido é tratado como em andamento. */
        public static ExecutionState of(String status) {
            if (status == null) {
                return RUNNING;
            }
            return switch (status) {
                case "SUCCEEDED" -> SUCCEEDED;
                case "FAILED" -> FAILED;
                case "TIMED_OUT" -> TIMED_OUT;
                case "ABORTED" -> ABORTED;
                default -> RUNNING;
            };
        }

        /** Encerrada sem passar pela consolidação: o {@code reconciler} finaliza a versão (T20). */
        public boolean endedWithoutConsolidation() {
            return this == FAILED || this == TIMED_OUT || this == ABORTED;
        }
    }

    public enum VersionAction {
        NONE,
        /** Consolida com os resultados existentes, grava {@code TERMINAL} e abre {@code WORKFLOW_FAILED}. */
        FINALIZE,
        /** Abre {@code STUCK_VERSION}; a versão continua como está. */
        STUCK
    }

    /**
     * Ação sobre uma versão {@code ACTIVE_VERSION}. A execução encerrada sem consolidar é finalizada mesmo antes
     * do prazo; a que segue viva, terminou com sucesso ou nunca começou só vira alerta depois de
     * {@code deadlineAt} + {@code stuckMargin}.
     */
    public static VersionAction versionAction(ExecutionState state, Instant deadlineAt, Duration stuckMargin,
            Instant now) {
        Objects.requireNonNull(state, "state");
        if (state.endedWithoutConsolidation()) {
            return VersionAction.FINALIZE;
        }
        if (deadlineAt != null && now.isAfter(deadlineAt.plus(stuckMargin))) {
            return VersionAction.STUCK;
        }
        return VersionAction.NONE;
    }

    /** {@code reasonCode} gravado no {@code RESULT} ainda aberto de uma versão finalizada pelo {@code reconciler}. */
    public static String endedResultReason(ExecutionState state) {
        return "WORKFLOW_" + state.name();
    }

    public enum ExpiredTaskReview {
        /** Sem ocorrência: só tira o waiter do índice {@code WAITER_EXPIRED}. */
        LEAVE_INDEX,
        /** Abre {@code TASK_EXPIRED} e tira o waiter do índice. */
        OPEN_TASK_EXPIRED,
        /** Deixa o waiter no índice para o próximo ciclo: a execução ainda pode usar o resultado. */
        WAIT
    }

    /**
     * Revisão de um waiter {@code EXPIRED}. Sem resultado gravado para ele, é o timeout normal da etapa, já
     * visível ao usuário no {@code RESULT}, e não abre ocorrência. Com resultado gravado, só abre
     * {@code TASK_EXPIRED} quando o histórico da execução, já encerrada, não mostra o resultado entregue. WHY: o
     * waiter fica {@code EXPIRED} também quando o {@code SendTaskSuccess} foi aceito antes de uma queda (§8) ou
     * quando o passo de timeout usou o resultado gravado; sem a regra, o alarme dispararia a cada timeout.
     */
    public static ExpiredTaskReview expiredTask(boolean resultRecorded, boolean deliveredInHistory,
            boolean executionEnded) {
        if (!resultRecorded || deliveredInHistory) {
            return ExpiredTaskReview.LEAVE_INDEX;
        }
        return executionEnded ? ExpiredTaskReview.OPEN_TASK_EXPIRED : ExpiredTaskReview.WAIT;
    }

    public enum DeadLetterDecision {
        /** Reenvia à fila de origem com a tentativa seguinte. */
        REDRIVE,
        /** Apaga a mensagem e registra {@code DEAD_LETTER}. */
        DISCARD
    }

    /**
     * Padrão do ADR 0007: {@code attempt} é o número da tentativa que levou a mensagem à DLQ, 1 na entrega
     * original. Abaixo de {@code maxAttempts}, a mensagem volta à fila; no limite, ou quando não identifica o
     * resultado, é descartada. Com {@code maxAttempts} 3, uma mensagem que sempre falha é reenviada duas vezes e
     * descartada na terceira chegada à DLQ. WHY: o prazo da versão não conta; a retomada grava o resultado fora do
     * prazo como {@code LATE} ou o descarta pelo {@code CONSUMED}, sem chamada externa.
     */
    public static DeadLetterDecision deadLetter(int attempt, int maxAttempts, boolean identified) {
        if (identified && attempt < maxAttempts) {
            return DeadLetterDecision.REDRIVE;
        }
        return DeadLetterDecision.DISCARD;
    }
}

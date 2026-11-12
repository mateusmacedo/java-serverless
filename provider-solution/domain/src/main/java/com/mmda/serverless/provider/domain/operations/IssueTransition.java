package com.mmda.serverless.provider.domain.operations;

/**
 * Mudança de uma ocorrência observada pelo reconciler (desenho, §5): cada mudança grava uma nova revisão e
 * publica um evento; observar de novo o mesmo problema aberto não muda nada.
 */
public enum IssueTransition {

    NONE,
    /** Primeira revisão, com {@code OperationIssueRaised}. */
    RAISE,
    /** Revisão seguinte, com {@code OperationIssueUpdated}. */
    UPDATE;

    /**
     * @param current    situação gravada, ou {@code null} quando a ocorrência não existe
     * @param wanted     situação observada agora
     * @param recurrence o problema aconteceu de novo, como outra mensagem descartada da mesma consulta; WHY: só a
     *                   recorrência atualiza uma ocorrência que continua aberta, para o ciclo de 1 min não publicar
     *                   um evento por minuto
     */
    public static IssueTransition of(IssueStatus current, IssueStatus wanted, boolean recurrence) {
        if (current == null) {
            return wanted == IssueStatus.OPEN ? RAISE : NONE;
        }
        if (current != wanted) {
            return UPDATE;
        }
        return wanted == IssueStatus.OPEN && recurrence ? UPDATE : NONE;
    }
}

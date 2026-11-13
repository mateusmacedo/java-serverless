package com.mmda.serverless.quote.domain;

import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Ocorrências operacionais e reconciliações manuais vistas pela API (§3, §4, §8
 * e §11 do desenho, M5): as da quote,
 * gravadas pelo {@code reconciler}, e as da provider, projetadas pelo
 * {@code issue-projector}.
 */
public final class Operations {

    /** Ação do item operacional no contrato. */
    public static final String RECONCILE = "RECONCILE";
    /** Valor do atributo {@code source} das ocorrências projetadas da provider. */
    public static final String PROVIDER_SOURCE = "provider";

    private Operations() {
    }

    /**
     * Tipos de ocorrência do contrato; o {@code DEAD_LETTER} existe nas duas
     * soluções.
     */
    public enum IssueType {
        UNKNOWN_QUERY(true),
        UNMATCHED_CALLBACK(true),
        DEAD_LETTER(true),
        STALE_OUTBOX(true),
        WORKFLOW_FAILED(false),
        TASK_EXPIRED(false),
        STUCK_VERSION(false);

        private final boolean provider;

        IssueType(boolean provider) {
            this.provider = provider;
        }

        /**
         * Tipo que a provider publica em {@code OperationIssueRaised} e
         * {@code OperationIssueUpdated}.
         */
        public boolean fromProvider() {
            return provider;
        }

        public static boolean isProviderType(String value) {
            for (IssueType type : values()) {
                if (type.name().equals(value)) {
                    return type.provider;
                }
            }
            return false;
        }
    }

    /**
     * Situação de uma reconciliação manual; a provider não publica o
     * {@code RUNNING} no MVP.
     */
    public enum OperationStatus {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /**
     * Identificador opaco de uma reconciliação manual, sem dado do operador nem da
     * consulta.
     */
    public static String newOperationId() {
        UUID uuid = UUID.randomUUID();
        return "op_" + HexFormat.of().toHexDigits(uuid.getMostSignificantBits())
                + HexFormat.of().toHexDigits(uuid.getLeastSignificantBits());
    }

    /**
     * {@code RECONCILE} só para a ocorrência aberta, com consulta, marcada como
     * reconciliável pela provider e para
     * quem tem {@code operations:reconcile} (§8 do desenho). WHY: a ocorrência
     * resolvida não tem mais o que
     * reconciliar, e o callback sem correlação fica só consultável, sem botão
     * indevido (contrato compartilhado).
     */
    public static List<String> issueActions(String queryId, boolean reconcilable, boolean open,
            Collection<Permission> permissions) {
        if (queryId != null && reconcilable && open && permissions.contains(Permission.OPERATIONS_RECONCILE)) {
            return List.of(RECONCILE);
        }
        return List.of();
    }
}

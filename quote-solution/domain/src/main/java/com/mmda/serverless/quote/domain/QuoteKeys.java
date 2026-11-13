package com.mmda.serverless.quote.domain;

/**
 * Chaves e valores de índice da tabela {@code quote-solution-<stage>-quotes} (§4 do desenho e ADR 0015).
 * Só strings: o mapeamento para o SDK fica na api e nas functions.
 */
public final class QuoteKeys {

    public static final String PK = "pk";
    public static final String SK = "sk";
    public static final String TYPE = "type";
    public static final String GSI1_PK = "gsi1pk";
    public static final String GSI1_SK = "gsi1sk";
    public static final String GSI2_PK = "gsi2pk";
    public static final String GSI2_SK = "gsi2sk";
    public static final String GSI1 = "gsi1";
    public static final String GSI2 = "gsi2";
    public static final String META = "META";

    public static final String ACTIVE_VERSION = "ACTIVE_VERSION";
    public static final String OUTBOX_PENDING = "OUTBOX_PENDING";
    public static final String WAITER_RESUMING = "WAITER_RESUMING";
    /** Waiter {@code EXPIRED} que o {@code reconciler} ainda não conferiu para o {@code TASK_EXPIRED} (M4). */
    public static final String WAITER_EXPIRED = "WAITER_EXPIRED";
    /**
     * Versão {@code TERMINAL} cujo resumo no {@code META} ainda não foi confirmado: gravado na consolidação e
     * retirado pelo {@code refreshSummary} ou pelo reparo do {@code reconciler} (M4).
     */
    public static final String SUMMARY_PENDING = "SUMMARY_PENDING";
    /**
     * Ocorrência da quote que se resolve sozinha ({@code STUCK_VERSION}), ainda aberta: o {@code reconciler} a
     * revisa e a resolve quando a situação some (M5). As projetadas da provider nunca entram neste índice.
     */
    public static final String ISSUE_OPEN = "ISSUE_OPEN";

    /** Valores do atributo {@code type}; o filtro do stream depende de {@code OUTBOX}. */
    public static final String TYPE_QUOTE = "QUOTE";
    public static final String TYPE_VERSION = "VERSION";
    public static final String TYPE_RESULT = "RESULT";
    public static final String TYPE_OFFER = "OFFER";
    public static final String TYPE_SELECTION = "SELECTION";
    public static final String TYPE_EVIDENCE = "EVIDENCE";
    public static final String TYPE_LATE = "LATE";
    public static final String TYPE_IDEMPOTENCY = "IDEMPOTENCY";
    public static final String TYPE_WAITER = "WAITER";
    public static final String TYPE_OUTBOX = "OUTBOX";
    public static final String TYPE_CONSUMED = "CONSUMED";
    /** Ocorrência ({@code ISSUE#}); o tipo da ocorrência fica em {@code issueType}, como na provider. */
    public static final String TYPE_ISSUE = "ISSUE";
    /** Consulta conhecida da organização ({@code QUERYREF#}), que autoriza o {@code RECONCILE} (M5). */
    public static final String TYPE_QUERYREF = "QUERYREF";
    /** Reconciliação manual pedida pela API ({@code OPERATION#}) (M5). */
    public static final String TYPE_OPERATION = "OPERATION";

    private QuoteKeys() {
    }

    public static String quotePk(String quoteId) {
        return "QUOTE#" + quoteId;
    }

    public static String versionSk(int version) {
        if (version < 1 || version > 9999) {
            throw new IllegalArgumentException("version out of range: " + version);
        }
        return "V#" + String.format("%04d", version);
    }

    public static String resultSk(int version, String insurerId) {
        return versionSk(version) + "#RESULT#" + insurerId;
    }

    public static String offerSk(int version, String offerId) {
        return versionSk(version) + "#OFFER#" + offerId;
    }

    public static String offerPrefix(int version) {
        return versionSk(version) + "#OFFER#";
    }

    public static String resultPrefix(int version) {
        return versionSk(version) + "#RESULT#";
    }

    public static String selectionSk(int version) {
        return versionSk(version) + "#SELECTION";
    }

    public static String evidenceSk(int version, WorkRef workRef) {
        return versionSk(version) + "#EVIDENCE#" + workRef.value();
    }

    /** Resultado tardio; {@code target} é o {@code insurerId} ou {@code ENRICHMENT}. */
    public static String lateSk(int version, String target, String queryId) {
        return versionSk(version) + "#LATE#" + target + "#" + queryId;
    }

    public static String waiterPk(WorkRef workRef) {
        return "WAITER#" + workRef.value();
    }

    public static String outboxPk(String eventId) {
        return "OUTBOX#" + eventId;
    }

    public static String consumedPk(String eventId) {
        return "CONSUMED#" + eventId;
    }

    public static String organizationIndexPk(String organizationId) {
        return "ORG#" + organizationId;
    }

    public static String issuePk(String issueId) {
        return "ISSUE#" + issueId;
    }

    /** {@code gsi1pk} das ocorrências de uma organização, lido pelo {@code GET /v1/operations/issues} (M5). */
    public static String issuesIndexPk(String organizationId) {
        return "ISSUES#" + organizationId;
    }

    public static String queryRefPk(String queryId) {
        return "QUERYREF#" + queryId;
    }

    public static String operationPk(String operationId) {
        return "OPERATION#" + operationId;
    }

    /**
     * Identificador da seleção: uma por versão (RN13). WHY: determinístico pela versão, então o
     * {@code OfferSelected} de uma versão tem sempre o mesmo {@code eventId}, mesmo numa corrida de pedidos.
     */
    public static String selectionId(String quoteId, int version) {
        return "sel_" + Hashes.sha256Hex("selection|" + quoteId + "|" + version).substring(0, 32);
    }

    public static String operationStatusUrl(String operationId) {
        return "/v1/operations/" + operationId;
    }

    /** Nome determinístico da execução; não leva dado pessoal (RN04). */
    public static String executionName(String quoteId, int version) {
        return "q-" + quoteId + "-v" + version;
    }

    /** Identificador da oferta: um por resultado de seguradora, estável na reentrega do resultado. */
    public static String offerId(WorkRef workRef) {
        return "of_" + Hashes.sha256Hex("offer|" + workRef.value()).substring(0, 32);
    }

    public static String statusUrl(String quoteId, int version) {
        return "/v1/quotes/" + quoteId + "/versions/" + version;
    }
}

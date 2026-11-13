package com.mmda.serverless.provider.domain;

import com.mmda.serverless.provider.domain.operations.IssueType;
import java.util.Objects;

/**
 * Identidades opacas e determinísticas da consulta, derivadas do
 * {@code workRef} do pedido
 * (desenho, §3 e §5). A reentrega do mesmo pedido chega sempre à mesma
 * consulta.
 */
public final class Identities {

    public static final String ENRICHMENT = "ENRICHMENT";
    public static final String INSURER = "INSURER";

    private static final int HEX_LENGTH = 32;

    private Identities() {
    }

    /**
     * {@code workRef} que a quote-solution grava no pedido: {@code wr_} e os 32
     * primeiros caracteres
     * hexadecimais do SHA-256 de {@code quoteId|version|kind|insurerId}, com
     * {@code insurerId} vazio no
     * enriquecimento. A provider recebe o valor pronto; o cálculo serve aos testes
     * e à conferência.
     */
    public static String workRef(String quoteId, int version, String kind, String insurerId) {
        String target = insurerId == null ? "" : insurerId;
        return "wr_" + prefix(String.join("|", quoteId, Integer.toString(version), kind, target));
    }

    /**
     * {@code q_} e os 32 primeiros caracteres hexadecimais do SHA-256 do
     * {@code workRef}.
     */
    public static String queryId(String workRef) {
        Objects.requireNonNull(workRef, "workRef");
        return "q_" + prefix(workRef);
    }

    /**
     * Evidência produzida por uma tentativa (ou pela conclusão sem chamada, com
     * sequência 0). Uma
     * evidência posterior da mesma consulta, como a da reconciliação, usa outra
     * sequência.
     */
    public static String evidenceId(String queryId, int attemptSequence) {
        return "ev_" + prefix(queryId + "|" + attemptSequence);
    }

    /**
     * Referência da tentativa lógica que a quote guarda no resultado da seguradora
     * (RN09).
     */
    public static String attemptRef(String queryId, int attemptSequence) {
        return queryId + "#" + sequence(attemptSequence);
    }

    /**
     * Ocorrência operacional: {@code is_} e os 32 primeiros caracteres hexadecimais
     * do SHA-256 de
     * {@code type|alvo}, com o {@code queryId}, o {@code externalEventId} ou o
     * {@code eventId} como alvo (§5). A
     * mesma situação observada de novo chega à mesma ocorrência.
     */
    public static String issueId(IssueType type, String target) {
        Objects.requireNonNull(target, "target");
        return "is_" + prefix(type.name() + "|" + target);
    }

    /**
     * Sequência da tentativa com quatro dígitos, como no {@code sk}
     * {@code ATTEMPT#<nnnn>}.
     */
    public static String sequence(int attemptSequence) {
        return "%04d".formatted(attemptSequence);
    }

    private static String prefix(String value) {
        return Digests.sha256Hex(value).substring(0, HEX_LENGTH);
    }
}

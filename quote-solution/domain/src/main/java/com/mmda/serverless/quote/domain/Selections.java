package com.mmda.serverless.quote.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Decisão da seleção de oferta (PRD, "Seleção e concorrência", RN11, RN13 e §8
 * do desenho), sem acesso à AWS. A
 * mesma precedência das ofertas indisponíveis vale aqui: versão substituída,
 * seleção existente e oferta expirada.
 * A transação confere de novo as mesmas condições; esta decisão só escolhe a
 * resposta antes de gravar.
 */
public final class Selections {

    private Selections() {
    }

    public enum Decision {
        /** Grava a intenção de proposta. */
        RECORD,
        /**
         * A versão já tem esta mesma oferta selecionada: devolve a seleção existente
         * (T28).
         */
        EXISTING,
        /**
         * Versão inexistente ou oferta que não pertence à versão: 404, sem revelar o
         * motivo.
         */
        NOT_FOUND,
        /**
         * A versão pedida não é a atual: 409 {@code VERSION_SUPERSEDED} (RF02, T17).
         */
        VERSION_SUPERSEDED,
        /**
         * A cotação já tem outra oferta selecionada: 409
         * {@code SELECTION_ALREADY_EXISTS} (T28).
         */
        SELECTION_ALREADY_EXISTS,
        /**
         * {@code validUntil} não é posterior ao relógio do servidor: 409
         * {@code OFFER_EXPIRED} (T18).
         */
        OFFER_EXPIRED
    }

    /** Seleção já gravada no {@code META} da cotação. */
    public record Existing(int version, String offerId) {
        public Existing {
            Objects.requireNonNull(offerId, "offerId");
        }
    }

    /**
     * @param existing   seleção gravada no {@code META}, nula quando não há
     * @param offerFound a oferta existe na versão pedida
     * @param validUntil validade da oferta encontrada
     */
    public static Decision decide(int version, int currentVersion, Existing existing, String offerId,
            boolean offerFound, Instant validUntil, Instant now) {
        Objects.requireNonNull(offerId, "offerId");
        if (version > currentVersion) {
            return Decision.NOT_FOUND;
        }
        if (existing != null) {
            if (existing.version() == version && existing.offerId().equals(offerId)) {
                return Decision.EXISTING;
            }
            return version != currentVersion ? Decision.VERSION_SUPERSEDED : Decision.SELECTION_ALREADY_EXISTS;
        }
        if (version != currentVersion) {
            return Decision.VERSION_SUPERSEDED;
        }
        if (!offerFound) {
            return Decision.NOT_FOUND;
        }
        if (validUntil == null || !validUntil.isAfter(now)) {
            return Decision.OFFER_EXPIRED;
        }
        return Decision.RECORD;
    }
}

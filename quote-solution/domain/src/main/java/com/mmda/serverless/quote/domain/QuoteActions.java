package com.mmda.serverless.quote.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Ações permitidas e disponibilidade das ofertas, derivadas na leitura (§8 do desenho). O
 * {@code serverTime} é o relógio do servidor no momento da leitura.
 */
public final class QuoteActions {

    public static final String CREATE_VERSION = "CREATE_VERSION";
    public static final String SELECT_OFFER = "SELECT_OFFER";

    private QuoteActions() {
    }

    /** Motivo de uma oferta não ser selecionável, na ordem de precedência do desenho. */
    public enum UnavailabilityReason {
        VERSION_SUPERSEDED,
        SELECTION_ALREADY_EXISTS,
        OFFER_EXPIRED
    }

    /** Estado da cotação que decide as ações: versão lida, versão atual e seleção existente. */
    public record QuoteState(int version, int currentVersion, boolean selected) {

        boolean isCurrent() {
            return version == currentVersion;
        }
    }

    /** Nulo quando a oferta é selecionável. */
    public static UnavailabilityReason unavailabilityReason(QuoteState state, Instant validUntil, Instant serverTime) {
        if (!state.isCurrent()) {
            return UnavailabilityReason.VERSION_SUPERSEDED;
        }
        if (state.selected()) {
            return UnavailabilityReason.SELECTION_ALREADY_EXISTS;
        }
        if (validUntil == null || !validUntil.isAfter(serverTime)) {
            return UnavailabilityReason.OFFER_EXPIRED;
        }
        return null;
    }

    public static List<String> allowedActions(QuoteState state, Collection<Permission> permissions,
            boolean anySelectableOffer) {
        List<String> actions = new ArrayList<>();
        if (state.isCurrent() && !state.selected()) {
            if (permissions.contains(Permission.QUOTES_WRITE)) {
                actions.add(CREATE_VERSION);
            }
            if (permissions.contains(Permission.OFFERS_SELECT) && anySelectableOffer) {
                actions.add(SELECT_OFFER);
            }
        }
        return actions;
    }
}

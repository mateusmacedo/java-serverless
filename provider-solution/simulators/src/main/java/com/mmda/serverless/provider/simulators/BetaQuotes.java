package com.mmda.serverless.provider.simulators;

import java.util.Optional;

/**
 * Situação da beta por {@code providerRequestId}, com um item auxiliar por
 * {@code clientReference} (§10).
 */
public interface BetaQuotes {

    void save(BetaQuote quote);

    /** Pelo {@code providerRequestId} ou, sem ele, pelo {@code clientReference}. */
    Optional<BetaQuote> find(String reference);
}

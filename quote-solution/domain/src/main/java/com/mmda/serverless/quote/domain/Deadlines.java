package com.mmda.serverless.quote.domain;

import java.time.Duration;
import java.time.Instant;

/** Prazos da versão e dos pedidos (§3 e §9 do desenho). */
public final class Deadlines {

    private Deadlines() {
    }

    public static Instant versionDeadline(Instant acceptedAt, Duration quoteDeadline) {
        return acceptedAt.plus(quoteDeadline);
    }

    /** Instante da {@code dispatch} mais o {@code TimeoutSeconds} da etapa, limitado ao prazo da versão. */
    public static Instant requestDeadline(Instant dispatchedAt, Duration stepTimeout, Instant versionDeadline) {
        Instant stepDeadline = dispatchedAt.plus(stepTimeout);
        return versionDeadline != null && versionDeadline.isBefore(stepDeadline) ? versionDeadline : stepDeadline;
    }
}

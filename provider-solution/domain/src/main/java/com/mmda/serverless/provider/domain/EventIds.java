package com.mmda.serverless.provider.domain;

import java.util.UUID;

/**
 * {@code eventId} determinístico dos fatos que podem ser gravados mais de uma vez (desenho, §3). O outbox
 * é gravado com {@code attribute_not_exists}, então a repetição não cria um segundo evento.
 */
public final class EventIds {

    /** Namespace URL da RFC 9562, o mesmo que a quote-solution usa nos {@code eventId} dela. */
    public static final UUID NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private EventIds() {
    }

    /** {@code EnrichmentResolved}: UUIDv5 de {@code queryId|workRef}, para o líder, cada seguidor e o HIT. */
    public static String enrichmentResolved(String queryId, String workRef) {
        return Digests.uuidV5(NAMESPACE, queryId + "|" + workRef).toString();
    }

    /** {@code InsurerResultRecorded}: UUIDv5 do {@code queryId}. */
    public static String insurerResultRecorded(String queryId) {
        return Digests.uuidV5(NAMESPACE, queryId).toString();
    }

    /** {@code EnrichmentRequested} e {@code InsurerQuoteRequested}: UUIDv5 do {@code workRef}. */
    public static String request(String workRef) {
        return Digests.uuidV5(NAMESPACE, workRef).toString();
    }
}

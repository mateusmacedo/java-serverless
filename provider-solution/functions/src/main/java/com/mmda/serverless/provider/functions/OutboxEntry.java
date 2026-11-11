package com.mmda.serverless.provider.functions;

import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Item {@code OUTBOX#<eventId>} pronto para publicar: tipo do evento e {@code detail} em JSON. */
record OutboxEntry(String eventId, String detailType, String detail) {

    static Optional<OutboxEntry> from(Map<String, AttributeValue> item) {
        Optional<String> eventId = Items.string(item, "eventId");
        Optional<String> detailType = Items.string(item, "detailType");
        Optional<String> detail = Items.string(item, "detail");
        if (eventId.isEmpty() || detailType.isEmpty() || detail.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OutboxEntry(eventId.get(), detailType.get(), detail.get()));
    }

    /** {@code eventId} de um item cujo conteúdo não pôde ser lido, para registrar a falha nele. */
    static Optional<String> eventIdOf(Map<String, AttributeValue> item) {
        return Items.string(item, Items.PK)
                .filter(pk -> pk.startsWith("OUTBOX#"))
                .map(pk -> pk.substring("OUTBOX#".length()));
    }
}

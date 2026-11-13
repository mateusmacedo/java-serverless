package com.mmda.serverless.provider.functions;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON dos eventos, das evidências e dos pedidos recebidos. Usa o
 * {@link ObjectMapper} do Spring, que omite
 * nulos ({@code spring.jackson.default-property-inclusion}) e ignora campos
 * desconhecidos.
 */
@Component
class EventJson {

    private final ObjectMapper objectMapper;

    EventJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /**
     * Lê o {@code detail} do envelope que a regra do EventBridge entrega na fila.
     *
     * @throws IllegalArgumentException se o corpo não for um evento do EventBridge
     *                                  com {@code detail}
     */
    <T> T detail(String messageBody, Class<T> type) {
        JsonNode envelope = objectMapper.readTree(messageBody);
        JsonNode detail = envelope.get("detail");
        if (detail == null || !detail.isObject()) {
            throw new IllegalArgumentException("The message is not an EventBridge event with a detail");
        }
        return objectMapper.treeToValue(detail, type);
    }

    /**
     * Lê o corpo de uma resposta de fornecedor; um corpo ilegível vira
     * {@code null}, sem exceção.
     */
    <T> T readOrNull(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }
}

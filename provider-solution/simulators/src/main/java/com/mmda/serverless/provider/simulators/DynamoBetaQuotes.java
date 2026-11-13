package com.mmda.serverless.provider.simulators;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code BETA#<providerRequestId>} e o auxiliar
 * {@code BETAREF#<clientReference>} na tabela dos simuladores, com
 * TTL de um dia: a situação só serve à reconciliação de pedidos recentes.
 */
@Component
class DynamoBetaQuotes implements BetaQuotes {

    private static final Duration TIME_TO_LIVE = Duration.ofDays(1);
    private static final TypeReference<Map<String, Object>> RESULT = new TypeReference<>() {
    };

    private final DynamoDbClient dynamoDb;
    private final ObjectMapper objectMapper;
    private final SimulatorProperties properties;
    private final Clock clock;

    DynamoBetaQuotes(DynamoDbClient dynamoDb, ObjectMapper objectMapper, SimulatorProperties properties, Clock clock) {
        this.dynamoDb = dynamoDb;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void save(BetaQuote quote) {
        AttributeValue ttl = AttributeValue.fromN(Long.toString(clock.instant().plus(TIME_TO_LIVE).getEpochSecond()));
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(quotePk(quote.providerRequestId())));
        item.put("providerRequestId", AttributeValue.fromS(quote.providerRequestId()));
        item.put("clientReference", AttributeValue.fromS(quote.clientReference()));
        item.put("scenario", AttributeValue.fromS(quote.scenario()));
        item.put("receivedAt", AttributeValue.fromS(quote.receivedAt().toString()));
        item.put("readyAt", AttributeValue.fromS(quote.readyAt().toString()));
        item.put("result", AttributeValue.fromS(objectMapper.writeValueAsString(quote.result())));
        item.put("ttl", ttl);
        dynamoDb.putItem(builder -> builder.tableName(properties.tableName()).item(item));
        dynamoDb.putItem(builder -> builder.tableName(properties.tableName()).item(Map.of(
                "pk", AttributeValue.fromS(referencePk(quote.clientReference())),
                "providerRequestId", AttributeValue.fromS(quote.providerRequestId()),
                "ttl", ttl)));
    }

    @Override
    public Optional<BetaQuote> find(String reference) {
        Map<String, AttributeValue> item = get(quotePk(reference));
        if (item.isEmpty()) {
            Map<String, AttributeValue> auxiliary = get(referencePk(reference));
            if (auxiliary.isEmpty()) {
                return Optional.empty();
            }
            item = get(quotePk(auxiliary.get("providerRequestId").s()));
        }
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BetaQuote(
                item.get("providerRequestId").s(),
                item.get("clientReference").s(),
                item.get("scenario").s(),
                Instant.parse(item.get("receivedAt").s()),
                Instant.parse(item.get("readyAt").s()),
                objectMapper.readValue(item.get("result").s(), RESULT)));
    }

    private Map<String, AttributeValue> get(String pk) {
        return dynamoDb.getItem(builder -> builder
                .tableName(properties.tableName())
                .consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS(pk))))
                .item();
    }

    private static String quotePk(String providerRequestId) {
        return "BETA#" + providerRequestId;
    }

    private static String referencePk(String clientReference) {
        return "BETAREF#" + clientReference;
    }
}

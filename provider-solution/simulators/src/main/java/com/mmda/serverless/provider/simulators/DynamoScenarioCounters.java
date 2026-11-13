package com.mmda.serverless.provider.simulators;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/**
 * Contadores na tabela dos simuladores, com TTL de uma hora: cada execução usa
 * uma referência nova (o
 * {@code clientReference}), então os itens antigos só ocupam espaço.
 */
@Component
class DynamoScenarioCounters implements ScenarioCounters {

    private static final Duration TIME_TO_LIVE = Duration.ofHours(1);

    private final DynamoDbClient dynamoDb;
    private final SimulatorProperties properties;
    private final Clock clock;

    DynamoScenarioCounters(DynamoDbClient dynamoDb, SimulatorProperties properties, Clock clock) {
        this.dynamoDb = dynamoDb;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public long increment(String key) {
        long expiresAt = clock.instant().plus(TIME_TO_LIVE).getEpochSecond();
        Map<String, AttributeValue> attributes = dynamoDb.updateItem(builder -> builder
                .tableName(properties.tableName())
                .key(Map.of("pk", AttributeValue.fromS(key)))
                .updateExpression("ADD calls :one SET #ttl = :ttl")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(Map.of(
                        ":one", AttributeValue.fromN("1"),
                        ":ttl", AttributeValue.fromN(Long.toString(expiresAt))))
                .returnValues(ReturnValue.UPDATED_NEW))
                .attributes();
        return Long.parseLong(attributes.get("calls").n());
    }
}

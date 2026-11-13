package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import io.floci.testcontainers.FlociContainer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Lambda dos simuladores pelo handler real, com o evento de proxy que o API Gateway entrega na rota
 * {@code /sim/{proxy+}}, e o contador do {@code vehicle-flaky} na tabela dos simuladores no Floci.
 */
class SimulatorsLambdaFlociIT {

    private static final FlociContainer FLOCI = new FlociContainer(System.getProperty("floci.image", "floci/floci:2.4.0"));
    private static final String TABLE = "it-provider-simulators";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StreamLambdaHandler HANDLER;

    static {
        FLOCI.start();
        System.setProperty("aws.endpointUrl", FLOCI.getEndpoint());
        System.setProperty("aws.region", FLOCI.getRegion());
        System.setProperty("aws.accessKeyId", FLOCI.getAccessKey());
        System.setProperty("aws.secretAccessKey", FLOCI.getSecretKey());
        System.setProperty("SIMULATORS_TABLE_NAME", TABLE);
        try (DynamoDbClient dynamoDb = DynamoDbClient.create()) {
            dynamoDb.createTable(builder -> builder
                    .tableName(TABLE)
                    .billingMode("PAY_PER_REQUEST")
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build()));
        }
        HANDLER = new StreamLambdaHandler();
    }

    @Test
    void flakyVehicleCountsCallsPerReferenceInTheSimulatorsTable() throws IOException {
        String body = "{\"clientReference\":\"q_it\",\"vehicleRef\":\"vehicle-flaky\",\"principalDriverRef\":\"driver-ana\"}";

        assertThat(invoke("/sim/enrichment/profiles", body).path("statusCode").asInt()).isEqualTo(503);
        assertThat(invoke("/sim/enrichment/profiles", body).path("statusCode").asInt()).isEqualTo(503);
        JsonNode third = invoke("/sim/enrichment/profiles", body);

        assertThat(third.path("statusCode").asInt()).isEqualTo(200);
        assertThat(JSON.readTree(third.path("body").asString()).path("vehicle").path("ref").asString())
                .isEqualTo("vehicle-flaky");
        try (DynamoDbClient dynamoDb = DynamoDbClient.create()) {
            Map<String, AttributeValue> counter = dynamoDb.getItem(builder -> builder
                    .tableName(TABLE)
                    .key(Map.of("pk", AttributeValue.fromS("FLAKY#q_it")))).item();
            assertThat(counter.get("calls").n()).isEqualTo("3");
            assertThat(counter).containsKey("ttl");
        }
    }

    @Test
    void alphaRouteAnswersThroughTheProxyEvent() throws IOException {
        JsonNode response = invoke("/sim/insurers/alpha/quotes",
                "{\"clientReference\":\"q_it\",\"risk\":{\"vehicle\":{\"ref\":\"vehicle-hatch-2019\"}},\"enrichment\":{}}");

        assertThat(response.path("statusCode").asInt()).isEqualTo(200);
        assertThat(JSON.readTree(response.path("body").asString()).path("outcome").asString()).isEqualTo("OFFER");
    }

    private static JsonNode invoke(String path, String body) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("resource", "/sim/{proxy+}");
        event.put("path", path);
        event.put("httpMethod", "POST");
        event.put("headers", Map.of("Content-Type", "application/json", "Accept", "application/json"));
        event.put("multiValueHeaders", Map.of("Content-Type", java.util.List.of("application/json"),
                "Accept", java.util.List.of("application/json")));
        event.put("pathParameters", Map.of("proxy", path.substring("/sim/".length())));
        event.put("requestContext", Map.of(
                "resourcePath", "/sim/{proxy+}",
                "httpMethod", "POST",
                "path", "/local" + path,
                "stage", "local",
                "requestId", "it-request",
                "identity", Map.of("sourceIp", "127.0.0.1")));
        event.put("body", body);
        event.put("isBase64Encoded", false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HANDLER.handleRequest(new ByteArrayInputStream(JSON.writeValueAsBytes(event)), output, null);
        return JSON.readTree(output.toString(StandardCharsets.UTF_8));
    }
}

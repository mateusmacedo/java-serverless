package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.provider.domain.insurer.CallbackSignature;
import com.sun.net.httpserver.HttpServer;
import io.floci.testcontainers.FlociContainer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Lambda dos simuladores pelo handler real, com o evento de proxy que o API
 * Gateway entrega na rota
 * {@code /sim/{proxy+}}, e o estado na tabela dos simuladores no Floci: o
 * contador do {@code vehicle-flaky} e a
 * situação da beta. A Lambda {@code simCallbackSender} entrega o callback
 * agendado na fila a uma API em processo.
 */
class SimulatorsLambdaFlociIT {

    private static final FlociContainer FLOCI = new FlociContainer(
            System.getProperty("floci.image", "floci/floci:2.4.0"));
    private static final String TABLE = "it-provider-simulators";
    private static final String CALLBACK_SECRET = "it/provider/callback-secret";
    private static final String CALLBACK_SECRET_VALUE = "it-callback-secret-value";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StreamLambdaHandler HANDLER;
    private static final String CALLBACKS_QUEUE_URL;
    private static final HttpServer CALLBACK_API;
    private static final AtomicReference<HttpExchangeCopy> RECEIVED = new AtomicReference<>();

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
        try (SqsClient sqs = SqsClient.create(); SecretsManagerClient secrets = SecretsManagerClient.create()) {
            CALLBACKS_QUEUE_URL = sqs.createQueue(builder -> builder.queueName("it-sim-callbacks")).queueUrl();
            secrets.createSecret(builder -> builder.name(CALLBACK_SECRET).secretString(CALLBACK_SECRET_VALUE));
        }
        CALLBACK_API = callbackApi();
        System.setProperty("SIM_CALLBACKS_QUEUE_URL", CALLBACKS_QUEUE_URL);
        System.setProperty("CALLBACK_SECRET_ID", CALLBACK_SECRET);
        System.setProperty("CALLBACKS_BASE_URL", "http://localhost:" + CALLBACK_API.getAddress().getPort() + "/local");
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
    void handlerProfilesACatalogVehicleFromTheCatalogOnItsClasspath() throws IOException {
        JsonNode response = invoke("/sim/enrichment/profiles",
                "{\"clientReference\":\"q_it_catalog\",\"vehicleRef\":\"vehicle-van-2022\",\"principalDriverRef\":\"driver-acme-bruno\"}");

        assertThat(response.path("statusCode").asInt()).isEqualTo(200);
        JsonNode vehicle = JSON.readTree(response.path("body").asString()).path("vehicle");
        assertThat(vehicle.path("make").asString()).isEqualTo("Renault");
        assertThat(vehicle.path("model").asString()).isEqualTo("Master");
        assertThat(vehicle.path("modelYear").asInt()).isEqualTo(2022);
    }

    @Test
    void alphaRouteAnswersThroughTheProxyEvent() throws IOException {
        JsonNode response = invoke("/sim/insurers/alpha/quotes",
                "{\"clientReference\":\"q_it\",\"risk\":{\"vehicle\":{\"ref\":\"vehicle-hatch-2019\"}},\"enrichment\":{}}");

        assertThat(response.path("statusCode").asInt()).isEqualTo(200);
        assertThat(JSON.readTree(response.path("body").asString()).path("outcome").asString()).isEqualTo("OFFER");
    }

    @Test
    void betaAcceptsSchedulesTheCallbackAndKeepsTheSituationByEitherReference() throws IOException {
        String clientReference = "q_" + "ab".repeat(16);

        JsonNode accepted = invoke("/sim/insurers/beta/quotes",
                """
                        {"clientReference":"%s","risk":{"usage":"PERSONAL","vehicle":{"ref":"vehicle-beta-early"}},                "enrichment":{}}"""
                        .formatted(clientReference));

        assertThat(accepted.path("statusCode").asInt()).isEqualTo(202);
        String providerRequestId = JSON.readTree(accepted.path("body").asString()).path("providerRequestId").asString();
        JsonNode order = JSON.readTree(receiveCallback().body());
        assertThat(order.path("providerRequestId").asString()).isEqualTo(providerRequestId);
        assertThat(order.has("clientReference")).as("the early callback carries only the providerRequestId").isFalse();
        assertThat(order.path("result").path("insurerName").asString()).isEqualTo("Beta Seguros");
        for (String reference : List.of(providerRequestId, clientReference)) {
            JsonNode status = invoke("GET", "/sim/insurers/beta/quotes/" + reference, null);
            assertThat(status.path("statusCode").asInt()).isEqualTo(200);
            JsonNode situation = JSON.readTree(status.path("body").asString());
            assertThat(situation.path("providerRequestId").asString()).isEqualTo(providerRequestId);
            assertThat(situation.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(situation.path("result").path("outcome").asString()).isEqualTo("OFFER");
        }
        assertThat(invoke("GET", "/sim/insurers/beta/quotes/beta-unknown", null).path("statusCode").asInt())
                .isEqualTo(404);
    }

    @Test
    void senderLambdaDeliversTheQueuedCallbackSignedWithTheCallbackSecret() throws IOException {
        invoke("/sim/insurers/beta/quotes", """
                {"clientReference":"q_%s","risk":{"vehicle":{"ref":"vehicle-beta-early"}},"enrichment":{}}"""
                .formatted("cd".repeat(16)));
        Message message = receiveCallback();
        String event = JSON.writeValueAsString(Map.of("Records", List.of(
                Map.of("messageId", message.messageId(), "body", message.body()))));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new SimCallbackSenderHandler().handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)),
                output, null);

        assertThat(JSON.readTree(output.toByteArray()).path("batchItemFailures").isEmpty()).isTrue();
        HttpExchangeCopy callback = RECEIVED.get();
        assertThat(callback.path()).isEqualTo("/local/v1/providers/beta/callbacks");
        assertThat(CallbackSignature.verify(CALLBACK_SECRET_VALUE.getBytes(StandardCharsets.UTF_8), callback.body(),
                callback.signature())).isTrue();
        assertThat(JSON.readTree(callback.body()).path("externalEventId").asString())
                .isEqualTo(JSON.readTree(message.body()).path("externalEventId").asString());
    }

    private static Message receiveCallback() {
        try (SqsClient sqs = SqsClient.create()) {
            List<Message> messages = sqs.receiveMessage(builder -> builder
                    .queueUrl(CALLBACKS_QUEUE_URL)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(5)).messages();
            assertThat(messages).hasSize(1);
            sqs.deleteMessage(builder -> builder.queueUrl(CALLBACKS_QUEUE_URL)
                    .receiptHandle(messages.getFirst().receiptHandle()));
            return messages.getFirst();
        }
    }

    private static HttpServer callbackApi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.set(new HttpExchangeCopy(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Signature"),
                        exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record HttpExchangeCopy(String path, String signature, byte[] body) {
    }

    private static JsonNode invoke(String path, String body) throws IOException {
        return invoke("POST", path, body);
    }

    private static JsonNode invoke(String method, String path, String body) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("resource", "/sim/{proxy+}");
        event.put("path", path);
        event.put("httpMethod", method);
        event.put("headers", Map.of("Content-Type", "application/json", "Accept", "application/json"));
        event.put("multiValueHeaders", Map.of("Content-Type", java.util.List.of("application/json"),
                "Accept", java.util.List.of("application/json")));
        event.put("pathParameters", Map.of("proxy", path.substring("/sim/".length())));
        event.put("requestContext", Map.of(
                "resourcePath", "/sim/{proxy+}",
                "httpMethod", method,
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

package com.mmda.serverless.provider.api;

import static com.mmda.serverless.provider.api.CallbackFixtures.OFFER;
import static com.mmda.serverless.provider.api.CallbackFixtures.callback;
import static com.mmda.serverless.provider.api.CallbackFixtures.givenInFlightQuery;
import static com.mmda.serverless.provider.api.CallbackFixtures.newEventId;
import static com.mmda.serverless.provider.api.CallbackFixtures.newProviderRequestId;
import static com.mmda.serverless.provider.api.CallbackFixtures.sign;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Lambda da API pelo handler real, com o evento de proxy que o API Gateway
 * entrega na rota
 * {@code /v1/providers/{provider}/callbacks}: a assinatura confere com os bytes
 * que o serverless-java-container
 * entrega ao controller.
 */
class StreamLambdaHandlerFlociIT extends FlociIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StreamLambdaHandler HANDLER = new StreamLambdaHandler();

    @Test
    void signedCallbackIsAcceptedThroughTheProxyEventAndAWrongSignatureIsNot() throws IOException {
        String providerRequestId = newProviderRequestId();
        CallbackFixtures.Query query = givenInFlightQuery(providerRequestId);
        String body = callback(newEventId(), providerRequestId, query.queryId(), OFFER);

        JsonNode rejected = invoke(body, sign(body + " "));
        JsonNode accepted = invoke(body, sign(body));

        assertThat(rejected.path("statusCode").asInt()).isEqualTo(401);
        assertThat(accepted.path("statusCode").asInt()).isEqualTo(200);
        assertThat(JSON.readTree(accepted.path("body").asString()).path("processingStatus").asString())
                .isEqualTo("PROCESSED");
        assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("COMPLETED");
    }

    private static JsonNode invoke(String body, String signature) throws IOException {
        String path = "/v1/providers/beta/callbacks";
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("resource", "/v1/providers/{provider}/callbacks");
        event.put("path", path);
        event.put("httpMethod", "POST");
        event.put("headers", Map.of("Content-Type", "application/json", "X-Signature", signature));
        event.put("multiValueHeaders", Map.of("Content-Type", List.of("application/json"),
                "X-Signature", List.of(signature)));
        event.put("pathParameters", Map.of("provider", "beta"));
        event.put("requestContext", Map.of(
                "resourcePath", "/v1/providers/{provider}/callbacks",
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

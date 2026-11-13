package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mmda.serverless.provider.domain.insurer.CallbackSignature;
import com.mmda.serverless.provider.domain.insurer.InsurerCallback;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code simCallbackSender} contra uma API de callbacks em processo. A
 * assinatura é conferida pela mesma
 * validação do {@code provider-api} ({@link CallbackSignature}, no
 * {@code provider-domain}).
 */
class SimCallbackSenderTest {

        private static final String SECRET = "it-callback-secret";
        /**
         * Callback que a API em processo recusa com {@code 401}; os demais recebem
         * {@link #status}.
         */
        private static final String REJECTED_EVENT = "bev-rejected";
        private static final JsonMapper JSON = JsonMapper.builder().build();

        private HttpServer api;
        private final AtomicReference<Received> received = new AtomicReference<>();
        private final AtomicInteger status = new AtomicInteger(202);
        private SimCallbackSender sender;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void start() throws IOException {
                api = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                api.createContext("/", exchange -> {
                        byte[] body = exchange.getRequestBody().readAllBytes();
                        received.set(new Received(exchange.getRequestURI().getPath(),
                                        exchange.getRequestHeaders().getFirst("X-Signature"),
                                        exchange.getRequestHeaders().getFirst("Content-Type"),
                                        body));
                        boolean rejected = new String(body, StandardCharsets.UTF_8).contains(REJECTED_EVENT);
                        exchange.sendResponseHeaders(rejected ? 401 : status.get(), -1);
                        exchange.close();
                });
                api.start();
                SecretsManagerClient secrets = mock(SecretsManagerClient.class);
                when(secrets.getSecretValue(any(Consumer.class)))
                                .thenReturn(GetSecretValueResponse.builder().secretString(SECRET).build());
                when(secrets.getSecretValue(any(GetSecretValueRequest.class)))
                                .thenReturn(GetSecretValueResponse.builder().secretString(SECRET).build());
                sender = new SimCallbackSender(new CallbackSecret(secrets, "secret"),
                                "http://localhost:" + api.getAddress().getPort() + "/local",
                                Clock.fixed(Instant.parse("2026-11-11T20:00:00Z"), ZoneOffset.UTC));
        }

        @AfterEach
        void stop() {
                api.stop(0);
        }

        @Test
        void signedCallbackIsAcceptedByTheValidationOfTheCallbackApi() {
                sender.send(order("q_0123456789abcdef0123456789abcdef"));

                Received callback = received.get();
                assertThat(callback.path()).isEqualTo("/local/v1/providers/beta/callbacks");
                assertThat(callback.contentType()).isEqualTo("application/json");
                assertThat(CallbackSignature.verify(SECRET.getBytes(StandardCharsets.UTF_8), callback.body(),
                                callback.signature())).isTrue();
                InsurerCallback parsed = JSON.readValue(callback.body(), InsurerCallback.class);
                assertThat(parsed.problems()).isEmpty();
                assertThat(parsed.externalEventId()).isEqualTo("bev-1");
                assertThat(parsed.providerRequestId()).isEqualTo("beta-1");
                assertThat(parsed.clientReference()).isEqualTo("q_0123456789abcdef0123456789abcdef");
                assertThat(parsed.sentAt()).isEqualTo("2026-11-11T20:00:00Z");
                assertThat(parsed.result().coverages().getFirst().description()).isEqualTo("Colisão, incêndio e roubo");
                assertThat(new String(callback.body(), StandardCharsets.US_ASCII))
                                .as("non-ASCII text is escaped, so the signed bytes survive any charset on the way")
                                .contains("Colis\\u00E3o");
        }

        @Test
        void earlyCallbackHasNoClientReference() {
                sender.send(order(null));

                JsonNode callback = JSON.readTree(received.get().body());
                assertThat(callback.has("clientReference")).isFalse();
                assertThat(callback.path("providerRequestId").asString()).isEqualTo("beta-1");
        }

        @Test
        void rejectedCallbackFailsOnlyItsOwnMessage() throws IOException {
                SimCallbackSenderHandler handler = new SimCallbackSenderHandler(sender);
                String event = JSON.writeValueAsString(Map.of("Records", List.of(
                                Map.of("messageId", "message-1", "body", order("bev-1", null)),
                                Map.of("messageId", "message-2", "body", order(REJECTED_EVENT, null)),
                                Map.of("messageId", "message-3", "body", order("bev-3", null)))));

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                handler.handleRequest(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), output, null);

                JsonNode failures = JSON.readTree(output.toByteArray()).path("batchItemFailures");
                assertThat(failures).singleElement()
                                .satisfies(failure -> assertThat(failure.path("itemIdentifier").asString())
                                                .isEqualTo("message-2"));
                assertThat(JSON.readTree(received.get().body()).path("externalEventId").asString())
                                .as("the batch goes on after the rejected message").isEqualTo("bev-3");
                assertThatThrownBy(() -> sender.send(order(REJECTED_EVENT, null)))
                                .isInstanceOf(SimCallbackSender.CallbackRejectedException.class)
                                .hasMessageContaining("401");
        }

        private static String order(String clientReference) {
                return order("bev-1", clientReference);
        }

        private static String order(String externalEventId, String clientReference) {
                return JSON.writeValueAsString(new CallbackOrder("beta", externalEventId, "beta-1", clientReference,
                                Map.of(
                                                "outcome", "OFFER",
                                                "offerNumber", "BETA-1",
                                                "insurerName", "Beta Seguros",
                                                "premium", Map.of("total", "2500.00", "currency", "BRL"),
                                                "coverages",
                                                List.of(Map.of("code", "COLLISION", "description",
                                                                "Colisão, incêndio e roubo",
                                                                "limit", "75000.00")),
                                                "expiresAt", "2026-12-01T00:00:00.000Z")));
        }

        private record Received(String path, String signature, String contentType, byte[] body) {
        }
}

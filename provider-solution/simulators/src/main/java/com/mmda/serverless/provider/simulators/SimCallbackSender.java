package com.mmda.serverless.provider.simulators;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega um callback agendado à API de callbacks da própria stack, como o
 * fornecedor faria: monta o corpo,
 * assina com o {@code callback-secret}
 * ({@code X-Signature: sha256=<hex do HMAC-SHA256 do corpo>}) e faz o POST.
 * Uma resposta fora de 2xx falha a mensagem, que volta à fila e, depois do
 * limite, vai para a DLQ.
 * <p>
 * WHY: o corpo sai em ASCII, com o resto escapado em JSON, para os bytes
 * assinados serem os mesmos que a API
 * recebe depois do API Gateway, qualquer que seja o charset que cada salto
 * suponha.
 */
class SimCallbackSender {

    static final String SIGNATURE_HEADER = "X-Signature";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HexFormat HEX = HexFormat.of();
    private static final JsonMapper ASCII_JSON = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .build();

    private final CallbackSecret secret;
    private final String callbacksBaseUrl;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** @param callbacksBaseUrl URL base da API de callbacks da própria stack */
    SimCallbackSender(CallbackSecret secret, String callbacksBaseUrl, Clock clock) {
        this.secret = secret;
        this.callbacksBaseUrl = callbacksBaseUrl;
        this.clock = clock;
    }

    /**
     * @param message corpo da mensagem da fila {@code sim-callbacks}, um
     *                {@link CallbackOrder}
     * @throws CallbackRejectedException se a API não responder 2xx
     */
    void send(String message) {
        CallbackOrder order = ASCII_JSON.readValue(message, CallbackOrder.class);
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("externalEventId", order.externalEventId());
        callback.put("providerRequestId", order.providerRequestId());
        if (order.clientReference() != null) {
            callback.put("clientReference", order.clientReference());
        }
        callback.put("sentAt", clock.instant().truncatedTo(ChronoUnit.MILLIS).toString());
        callback.put("result", order.result());
        byte[] body = ASCII_JSON.writeValueAsBytes(callback);
        HttpRequest request = HttpRequest.newBuilder(callbackUri(order.provider()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, sign(secret.value(), body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        int status = post(request);
        if (status / 100 != 2) {
            throw new CallbackRejectedException(order.externalEventId(), status);
        }
    }

    static String sign(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return "sha256=" + HEX.formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }

    private URI callbackUri(String provider) {
        return URI.create(callbacksBaseUrl + "/v1/providers/" + provider + "/callbacks");
    }

    private int post(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException exception) {
            throw new CallbackRejectedException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CallbackRejectedException(exception);
        }
    }

    static final class CallbackRejectedException extends RuntimeException {

        CallbackRejectedException(String externalEventId, int status) {
            super("The callback API answered " + status + " to " + externalEventId);
        }

        CallbackRejectedException(Exception cause) {
            super("The callback API could not be reached", cause);
        }
    }
}

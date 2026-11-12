package com.mmda.serverless.provider.functions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fornecedores no protocolo dos simuladores, servidos em processo, para os testes de integração escolherem a
 * resposta pelo veículo e contarem as chamadas por {@code clientReference}. O {@code vehicle-slow} demora
 * mais que o timeout HTTP dos testes, para simular resposta perdida depois do envio. Uma chamada retida por
 * {@link #hold(String)} só responde quando o teste solta, como um fornecedor lento; a retenção que vence sozinha
 * fica em {@link #expiredHolds()}, para o teste distinguir a soltura dele da expiração. O
 * {@code vehicle-observed-ahead} responde com o {@code observedAt} duas horas à frente do relógio.
 */
final class ProviderStub {

    static final String VEHICLE_FLAKY = "vehicle-flaky";
    static final String VEHICLE_ENRICHMENT_DOWN = "vehicle-enrichment-down";
    static final String VEHICLE_UNKNOWN = "vehicle-unknown";
    static final String VEHICLE_ALPHA_DECLINES = "vehicle-alpha-declines";
    static final String VEHICLE_ALPHA_DOWN = "vehicle-alpha-down";
    static final String VEHICLE_SLOW = "vehicle-slow";
    static final String VEHICLE_OBSERVED_AHEAD = "vehicle-observed-ahead";
    static final Duration OBSERVED_AHEAD = Duration.ofHours(2);
    static final long SLOW_MILLIS = 2_500;
    /** Teto da retenção; o timeout HTTP do teste que retém respostas fica acima dele. */
    static final Duration MAX_HOLD = Duration.ofSeconds(50);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> enrichmentCallsByVehicle = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private final Set<String> expiredHolds = ConcurrentHashMap.newKeySet();

    private ProviderStub(HttpServer server) {
        this.server = server;
    }

    static ProviderStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ProviderStub stub = new ProviderStub(server);
            server.createContext("/sim/enrichment/profiles", stub::enrichment);
            server.createContext("/sim/insurers/", stub::insurer);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return stub;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Chamadas recebidas para a consulta, em qualquer fornecedor. */
    int calls(String clientReference) {
        AtomicInteger count = calls.get(clientReference);
        return count == null ? 0 : count.get();
    }

    /** Chamadas de enriquecimento recebidas para o veículo, de qualquer consulta. */
    int enrichmentCalls(String vehicleRef) {
        AtomicInteger count = enrichmentCallsByVehicle.get(vehicleRef);
        return count == null ? 0 : count.get();
    }

    /**
     * Retém a resposta das chamadas cujo veículo ou {@code clientReference} é {@code key} até o teste soltar,
     * por no máximo {@link #MAX_HOLD}.
     */
    CountDownLatch hold(String key) {
        CountDownLatch release = new CountDownLatch(1);
        holds.put(key, release);
        return release;
    }

    /** Chaves cuja retenção venceu por {@link #MAX_HOLD} antes de o teste soltar. */
    Set<String> expiredHolds() {
        return Set.copyOf(expiredHolds);
    }

    private void enrichment(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        String reference = request.path("clientReference").asString();
        int call = calls.computeIfAbsent(reference, key -> new AtomicInteger()).incrementAndGet();
        String vehicle = request.path("vehicleRef").asString();
        enrichmentCallsByVehicle.computeIfAbsent(vehicle, key -> new AtomicInteger()).incrementAndGet();
        awaitRelease(vehicle);
        awaitRelease(reference);
        switch (vehicle) {
            case VEHICLE_ENRICHMENT_DOWN -> respond(exchange, 504, "{\"code\":\"UPSTREAM_TIMEOUT\"}");
            case VEHICLE_UNKNOWN -> respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"notFoundRefs\":[\"vehicleRef\"]}");
            case VEHICLE_SLOW -> slow(exchange);
            default -> {
                if (VEHICLE_FLAKY.equals(vehicle) && call <= 2) {
                    respond(exchange, 503, "{\"code\":\"TEMPORARILY_UNAVAILABLE\"}");
                } else {
                    Instant observedAt = TestClock.INSTANCE.instant();
                    if (VEHICLE_OBSERVED_AHEAD.equals(vehicle)) {
                        observedAt = observedAt.plus(OBSERVED_AHEAD);
                    }
                    respond(exchange, 200, """
                            {"clientReference":"%s","vehicle":{"ref":"%s","make":"Fiat","model":"Argo","modelYear":2019,\
                            "marketValue":"68500.00"},"driver":{"ref":"%s","licenseYears":8,"claimsLast5Years":0},\
                            "observedAt":"%s","unknownField":true}"""
                            .formatted(reference, vehicle, request.path("principalDriverRef").asString(), observedAt));
                }
            }
        }
    }

    private void insurer(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        String reference = request.path("clientReference").asString();
        calls.computeIfAbsent(reference, key -> new AtomicInteger()).incrementAndGet();
        String vehicle = request.path("risk").path("vehicle").path("ref").asString();
        switch (vehicle) {
            case VEHICLE_ALPHA_DOWN -> respond(exchange, 503, "{\"code\":\"TEMPORARILY_UNAVAILABLE\"}");
            case VEHICLE_ALPHA_DECLINES -> respond(exchange, 200,
                    "{\"clientReference\":\"%s\",\"outcome\":\"DECLINED\",\"declineCode\":\"RISK_NOT_ACCEPTED\"}"
                            .formatted(reference));
            case VEHICLE_SLOW -> slow(exchange);
            default -> respond(exchange, 200, """
                    {"clientReference":"%s","outcome":"OFFER","offerNumber":"ALPHA-%s","insurerName":"Alpha Seguros",\
                    "premium":{"total":"2450.00","currency":"BRL"},\
                    "paymentOptions":[{"installments":1,"installmentAmount":"2450.00"},\
                    {"installments":10,"installmentAmount":"245.00"}],\
                    "coverages":[{"code":"COLLISION","description":"Colisão, incêndio e roubo","limit":"68500.00"}],\
                    "deductibles":[{"coverage":"COLLISION","amount":"3425.00"}],\
                    "expiresAt":"2026-12-01T00:00:00.000Z"}""".formatted(reference, reference.substring(2, 10)));
        }
    }

    private void awaitRelease(String key) {
        CountDownLatch release = holds.get(key);
        if (release == null) {
            return;
        }
        try {
            if (!release.await(MAX_HOLD.toMillis(), TimeUnit.MILLISECONDS)) {
                expiredHolds.add(key);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void slow(HttpExchange exchange) throws IOException {
        try {
            Thread.sleep(SLOW_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        respond(exchange, 200, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException exception) {
            exchange.close();
        }
    }
}

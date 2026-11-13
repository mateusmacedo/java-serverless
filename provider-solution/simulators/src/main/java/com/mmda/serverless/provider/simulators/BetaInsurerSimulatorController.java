package com.mmda.serverless.provider.simulators;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seguradora assíncrona beta (§10): aceita o pedido com {@code 202} e um {@code providerRequestId} e entrega o
 * resultado por callback, agendado na fila {@code sim-callbacks}. O veículo escolhe o cenário, e as coberturas
 * são as do perfil pedido no catálogo. O resultado e o agendamento são decididos antes da resposta, então o
 * {@code vehicle-beta-early} chega antes do {@code 202} gravado pelo worker.
 */
@RestController
class BetaInsurerSimulatorController {

    static final String PROVIDER = "beta";
    static final String INSURER_NAME = "Beta Seguros";
    static final int DUPLICATE_GAP_SECONDS = 2;
    private static final BigDecimal PREMIUM_FACTOR = new BigDecimal("1.06");
    private static final String DEDUCTIBLE_COVERAGE = "COLLISION";
    private static final BigDecimal DEDUCTIBLE_RATE = new BigDecimal("0.04");
    private static final int MAX_INSTALLMENTS = 12;

    private final BetaQuotes quotes;
    private final ScheduledCallbacks callbacks;
    private final SimulatorProperties properties;
    private final LabCatalog catalog;
    private final Clock clock;

    BetaInsurerSimulatorController(BetaQuotes quotes, ScheduledCallbacks callbacks, SimulatorProperties properties,
                                   LabCatalog catalog, Clock clock) {
        this.quotes = quotes;
        this.callbacks = callbacks;
        this.properties = properties;
        this.catalog = catalog;
        this.clock = clock;
    }

    @PostMapping(path = "/sim/insurers/beta/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> quote(@RequestBody AlphaInsurerSimulatorController.QuoteRequest request) {
        String vehicleRef = RiskFields.vehicleRef(request.risk());
        if (request.clientReference() == null || request.clientReference().isBlank() || vehicleRef == null) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST",
                    "clientReference and risk.vehicle are required"));
        }
        Optional<LabCatalog.CoverageProfile> profile = catalog.coverageProfile(
                RiskFields.coverageProfile(request.risk()));
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST",
                    "risk.coverageProfile is not in the catalog"));
        }
        BetaScenario scenario = BetaScenario.of(vehicleRef);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String providerRequestId = "beta-" + UUID.randomUUID();
        List<Delivery> deliveries = deliveries(scenario);
        int readyAfter = deliveries.isEmpty() ? properties.betaCallbackDelaySeconds() : deliveries.getFirst().delaySeconds();
        Map<String, Object> result = result(scenario, request.clientReference(), vehicleRef,
                RiskFields.usage(request.risk()), profile.get(), now);
        quotes.save(new BetaQuote(providerRequestId, request.clientReference(), scenario.name(), now,
                now.plusSeconds(readyAfter), result));
        String externalEventId = "bev-" + UUID.randomUUID();
        for (Delivery delivery : deliveries) {
            callbacks.schedule(new CallbackOrder(PROVIDER, externalEventId, providerRequestId,
                    delivery.withClientReference() ? request.clientReference() : null, result), delivery.delaySeconds());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Accepted(request.clientReference(), providerRequestId, "PROCESSING"));
    }

    /** Situação para a reconciliação (M4), por {@code providerRequestId} ou {@code clientReference}. */
    @GetMapping(path = "/sim/insurers/beta/quotes/{reference}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> status(@PathVariable String reference) {
        return quotes.find(reference)
                .<ResponseEntity<Object>>map(quote -> ResponseEntity.ok(status(quote, clock.instant())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("NOT_FOUND", null)));
    }

    private static Status status(BetaQuote quote, Instant now) {
        boolean completed = quote.completedAt(now);
        return new Status(quote.providerRequestId(), quote.clientReference(), completed ? "COMPLETED" : "PROCESSING",
                quote.receivedAt().toString(), completed ? quote.readyAt().toString() : null,
                completed ? quote.result() : null);
    }

    /**
     * Um callback por cenário, com o {@code clientReference}; o duplicado repete o mesmo {@code externalEventId},
     * o atrasado passa da espera da seguradora e o antecipado sai sem atraso e só com o {@code providerRequestId}.
     */
    private List<Delivery> deliveries(BetaScenario scenario) {
        int delay = properties.betaCallbackDelaySeconds();
        return switch (scenario) {
            case SILENT -> List.of();
            case DUPLICATE -> List.of(new Delivery(delay, true), new Delivery(delay + DUPLICATE_GAP_SECONDS, true));
            case LATE -> List.of(new Delivery(properties.betaLateCallbackDelaySeconds(), true));
            case EARLY -> List.of(new Delivery(0, false));
            default -> List.of(new Delivery(delay, true));
        };
    }

    private Map<String, Object> result(BetaScenario scenario, String clientReference, String vehicleRef, String usage,
                                       LabCatalog.CoverageProfile profile, Instant now) {
        return switch (scenario) {
            case DECLINES -> Map.of("outcome", "DECLINED", "declineCode", "RISK_NOT_ACCEPTED");
            case NEEDS_INFO -> Map.of("outcome", "PENDING_INFORMATION", "pendingFields", List.of(Map.of(
                    "field", "risk.principalDriver.licenseDate", "code", "MISSING_LICENSE_DATE")));
            default -> offer(clientReference, vehicleRef, usage, profile, now);
        };
    }

    private Map<String, Object> offer(String clientReference, String vehicleRef, String usage,
                                      LabCatalog.CoverageProfile profile, Instant now) {
        BigDecimal total = SyntheticData.basePremium(vehicleRef, usage).multiply(PREMIUM_FACTOR)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal installment = total.divide(BigDecimal.valueOf(MAX_INSTALLMENTS), 2, RoundingMode.HALF_UP);
        Map<String, Object> offer = new LinkedHashMap<>();
        offer.put("outcome", "OFFER");
        offer.put("offerNumber", "BETA-" + SyntheticData.shortCode(clientReference));
        offer.put("insurerName", INSURER_NAME);
        offer.put("premium", Map.of("total", total.toPlainString(), "currency", "BRL"));
        offer.put("paymentOptions", List.of(
                Map.of("installments", 1, "installmentAmount", total.toPlainString()),
                Map.of("installments", MAX_INSTALLMENTS, "installmentAmount", installment.toPlainString())));
        offer.put("coverages", profile.coverages().stream()
                .map(coverage -> Map.of("code", coverage.code(), "description", coverage.label(),
                        "limit", coverage.insuredAmount()))
                .toList());
        offer.put("deductibles", profile.coverage(DEDUCTIBLE_COVERAGE)
                .map(coverage -> Map.of("coverage", coverage.code(), "amount", new BigDecimal(coverage.insuredAmount())
                        .multiply(DEDUCTIBLE_RATE).setScale(2, RoundingMode.HALF_UP).toPlainString()))
                .stream().toList());
        offer.put("expiresAt", now.plusSeconds(properties.offerValiditySeconds()).truncatedTo(ChronoUnit.MILLIS)
                .toString());
        return offer;
    }

    /** Cenário pelo veículo do catálogo; {@code OFFER} vale para qualquer outro veículo. */
    enum BetaScenario {
        OFFER(null),
        DECLINES("vehicle-beta-declines"),
        SILENT("vehicle-beta-silent"),
        DUPLICATE("vehicle-beta-duplicate"),
        LATE("vehicle-beta-late"),
        NEEDS_INFO("vehicle-beta-needs-info"),
        EARLY("vehicle-beta-early");

        private final String vehicleRef;

        BetaScenario(String vehicleRef) {
            this.vehicleRef = vehicleRef;
        }

        String vehicleRef() {
            return vehicleRef;
        }

        static BetaScenario of(String vehicleRef) {
            for (BetaScenario scenario : values()) {
                if (vehicleRef.equals(scenario.vehicleRef)) {
                    return scenario;
                }
            }
            return OFFER;
        }
    }

    private record Delivery(int delaySeconds, boolean withClientReference) {
    }

    record Accepted(String clientReference, String providerRequestId, String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Status(String providerRequestId, String clientReference, String status, String receivedAt,
                  String completedAt, Map<String, Object> result) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(String code, String message) {
    }
}

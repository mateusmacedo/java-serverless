package com.mmda.serverless.provider.simulators;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seguradora síncrona alpha (§10): responde a oferta na própria chamada; recusa
 * o
 * {@code vehicle-alpha-declines} e fica indisponível (503) para o
 * {@code vehicle-alpha-down}. As coberturas da
 * oferta são as do perfil pedido no catálogo do laboratório; perfil fora do
 * catálogo é pedido inválido.
 */
@RestController
class AlphaInsurerSimulatorController {

        static final String VEHICLE_ALPHA_DECLINES = "vehicle-alpha-declines";
        static final String VEHICLE_ALPHA_DOWN = "vehicle-alpha-down";
        static final String INSURER_NAME = "Alpha Seguros";
        private static final String DEDUCTIBLE_COVERAGE = "COLLISION";
        private static final BigDecimal DEDUCTIBLE_RATE = new BigDecimal("0.05");
        private static final int MAX_INSTALLMENTS = 10;

        private final SimulatorProperties properties;
        private final LabCatalog catalog;
        private final Clock clock;

        AlphaInsurerSimulatorController(SimulatorProperties properties, LabCatalog catalog, Clock clock) {
                this.properties = properties;
                this.catalog = catalog;
                this.clock = clock;
        }

        @PostMapping(path = "/sim/insurers/alpha/quotes", produces = MediaType.APPLICATION_JSON_VALUE)
        ResponseEntity<Object> quote(@RequestBody QuoteRequest request) {
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
                if (VEHICLE_ALPHA_DOWN.equals(vehicleRef)) {
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                        .body(new ErrorBody("TEMPORARILY_UNAVAILABLE", null));
                }
                if (VEHICLE_ALPHA_DECLINES.equals(vehicleRef)) {
                        return ResponseEntity
                                        .ok(new Decline(request.clientReference(), "DECLINED", "RISK_NOT_ACCEPTED"));
                }
                return ResponseEntity.ok(offer(request.clientReference(), vehicleRef, RiskFields.usage(request.risk()),
                                profile.get()));
        }

        private Offer offer(String clientReference, String vehicleRef, String usage,
                        LabCatalog.CoverageProfile profile) {
                BigDecimal total = SyntheticData.basePremium(vehicleRef, usage);
                BigDecimal installment = total.divide(BigDecimal.valueOf(MAX_INSTALLMENTS), 2, RoundingMode.HALF_UP);
                String expiresAt = clock.instant().plusSeconds(properties.offerValiditySeconds())
                                .truncatedTo(ChronoUnit.MILLIS).toString();
                return new Offer(
                                clientReference,
                                "OFFER",
                                "ALPHA-" + SyntheticData.shortCode(clientReference),
                                INSURER_NAME,
                                new Premium(total.toPlainString(), "BRL"),
                                List.of(new PaymentOption(1, total.toPlainString()),
                                                new PaymentOption(MAX_INSTALLMENTS, installment.toPlainString())),
                                profile.coverages().stream()
                                                .map(coverage -> new Coverage(coverage.code(), coverage.label(),
                                                                coverage.insuredAmount()))
                                                .toList(),
                                profile.coverage(DEDUCTIBLE_COVERAGE)
                                                .map(coverage -> new Deductible(coverage.code(),
                                                                new BigDecimal(coverage.insuredAmount())
                                                                                .multiply(DEDUCTIBLE_RATE)
                                                                                .setScale(2, RoundingMode.HALF_UP)
                                                                                .toPlainString()))
                                                .stream().toList(),
                                expiresAt);
        }

        record QuoteRequest(String clientReference, Map<String, Object> risk, Map<String, Object> enrichment) {
        }

        record Offer(String clientReference, String outcome, String offerNumber, String insurerName, Premium premium,
                        List<PaymentOption> paymentOptions, List<Coverage> coverages, List<Deductible> deductibles,
                        String expiresAt) {
        }

        record Premium(String total, String currency) {
        }

        record PaymentOption(int installments, String installmentAmount) {
        }

        record Coverage(String code, String description, String limit) {
        }

        record Deductible(String coverage, String amount) {
        }

        record Decline(String clientReference, String outcome, String declineCode) {
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        record ErrorBody(String code, String message) {
        }
}

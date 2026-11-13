package com.mmda.serverless.provider.simulators;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provedor de enriquecimento simulado (§10): 404 {@code NOT_FOUND} declarado
 * para referência desconhecida, 503 nas
 * duas primeiras chamadas de cada {@code clientReference} do
 * {@code vehicle-flaky} e 504 sempre para o
 * {@code vehicle-enrichment-down}. Marca, modelo, ano e habilitação vêm do
 * catálogo do laboratório; o resto do
 * perfil, e o perfil inteiro de referência fora do catálogo, é sintético.
 */
@RestController
class EnrichmentSimulatorController {

    static final String VEHICLE_FLAKY = "vehicle-flaky";
    static final String VEHICLE_ENRICHMENT_DOWN = "vehicle-enrichment-down";
    static final int FLAKY_FAILURES = 2;
    /**
     * WHY: o {@code licenseDate} do catálogo é data civil no fuso do contrato, e
     * não em UTC.
     */
    static final ZoneId CIVIL_ZONE = ZoneId.of("America/Sao_Paulo");

    private final ScenarioCounters counters;
    private final LabCatalog catalog;
    private final Clock clock;

    EnrichmentSimulatorController(ScenarioCounters counters, LabCatalog catalog, Clock clock) {
        this.counters = counters;
        this.catalog = catalog;
        this.clock = clock;
    }

    @PostMapping(path = "/sim/enrichment/profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> profile(@RequestBody ProfileRequest request) {
        if (isBlank(request.clientReference()) || isBlank(request.vehicleRef())
                || isBlank(request.principalDriverRef())) {
            return error(HttpStatus.BAD_REQUEST, new ErrorBody("INVALID_REQUEST",
                    "clientReference, vehicleRef and principalDriverRef are required", null));
        }
        if (VEHICLE_ENRICHMENT_DOWN.equals(request.vehicleRef())) {
            return error(HttpStatus.GATEWAY_TIMEOUT, new ErrorBody("UPSTREAM_TIMEOUT", null, null));
        }
        if (VEHICLE_FLAKY.equals(request.vehicleRef())
                && counters.increment("FLAKY#" + request.clientReference()) <= FLAKY_FAILURES) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, new ErrorBody("TEMPORARILY_UNAVAILABLE", null, null));
        }
        List<String> notFoundRefs = new ArrayList<>();
        if (SyntheticData.isUnknown(request.vehicleRef())) {
            notFoundRefs.add("vehicleRef");
        }
        if (SyntheticData.isUnknown(request.principalDriverRef())) {
            notFoundRefs.add("principalDriverRef");
        }
        if (!notFoundRefs.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, new ErrorBody("NOT_FOUND", null, notFoundRefs));
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return ResponseEntity.ok(new ProfileResponse(request.clientReference(), vehicle(request.vehicleRef()),
                driver(request.principalDriverRef(), LocalDate.ofInstant(now, CIVIL_ZONE)), now.toString()));
    }

    private VehicleBody vehicle(String ref) {
        SyntheticData.VehicleData synthetic = SyntheticData.vehicle(ref);
        String marketValue = synthetic.marketValue().toPlainString();
        return catalog.vehicle(ref)
                .map(vehicle -> new VehicleBody(ref, vehicle.make(), vehicle.model(), vehicle.year(), marketValue))
                .orElseGet(() -> new VehicleBody(ref, synthetic.make(), synthetic.model(), synthetic.modelYear(),
                        marketValue));
    }

    private DriverBody driver(String ref, LocalDate today) {
        SyntheticData.DriverData synthetic = SyntheticData.driver(ref);
        int licenseYears = catalog.driver(ref)
                .map(driver -> driver.licenseYears(today))
                .orElse(synthetic.licenseYears());
        return new DriverBody(ref, licenseYears, synthetic.claimsLast5Years());
    }

    private static ResponseEntity<Object> error(HttpStatus status, ErrorBody body) {
        return ResponseEntity.status(status).body(body);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record ProfileRequest(String clientReference, String vehicleRef, String principalDriverRef) {
    }

    record ProfileResponse(String clientReference, VehicleBody vehicle, DriverBody driver, String observedAt) {
    }

    record VehicleBody(String ref, String make, String model, int modelYear, String marketValue) {
    }

    record DriverBody(String ref, int licenseYears, int claimsLast5Years) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(String code, String message, List<String> notFoundRefs) {
    }
}

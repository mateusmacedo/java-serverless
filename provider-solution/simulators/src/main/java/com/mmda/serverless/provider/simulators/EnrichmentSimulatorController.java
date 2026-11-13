package com.mmda.serverless.provider.simulators;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
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
 * Provedor de enriquecimento simulado (§10): perfil do veículo e do condutor, 404 {@code NOT_FOUND}
 * declarado para referência desconhecida, 503 nas duas primeiras chamadas de cada {@code clientReference}
 * do {@code vehicle-flaky} e 504 sempre para o {@code vehicle-enrichment-down}.
 */
@RestController
class EnrichmentSimulatorController {

    static final String VEHICLE_FLAKY = "vehicle-flaky";
    static final String VEHICLE_ENRICHMENT_DOWN = "vehicle-enrichment-down";
    static final int FLAKY_FAILURES = 2;

    private final ScenarioCounters counters;
    private final Clock clock;

    EnrichmentSimulatorController(ScenarioCounters counters, Clock clock) {
        this.counters = counters;
        this.clock = clock;
    }

    @PostMapping(path = "/sim/enrichment/profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> profile(@RequestBody ProfileRequest request) {
        if (isBlank(request.clientReference()) || isBlank(request.vehicleRef()) || isBlank(request.principalDriverRef())) {
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
        SyntheticData.VehicleData vehicle = SyntheticData.vehicle(request.vehicleRef());
        SyntheticData.DriverData driver = SyntheticData.driver(request.principalDriverRef());
        return ResponseEntity.ok(new ProfileResponse(
                request.clientReference(),
                new VehicleBody(vehicle.ref(), vehicle.make(), vehicle.model(), vehicle.modelYear(),
                        vehicle.marketValue().toPlainString()),
                new DriverBody(driver.ref(), driver.licenseYears(), driver.claimsLast5Years()),
                clock.instant().truncatedTo(ChronoUnit.MILLIS).toString()));
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

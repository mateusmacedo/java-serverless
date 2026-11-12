package com.mmda.serverless.provider.simulators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.Period;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cadastro sintético do laboratório: o mesmo {@code contracts/fixtures/catalog.json} que a quote e o frontend usam,
 * empacotado no zip como recurso de classpath. Os simuladores tiram dele os veículos, os condutores e os perfis de
 * cobertura; o comportamento de cada cenário continua decidido pelo id do veículo (desenho, §10). Os ids são únicos
 * entre as organizações, porque o fornecedor recebe só a referência.
 */
final class LabCatalog {

    static final String RESOURCE = "fixtures/catalog.json";
    /** Perfil usado quando o {@code risk} não traz {@code coverageProfile}, como nos pedidos sem snapshot completo. */
    static final String DEFAULT_COVERAGE_PROFILE = "COMPREHENSIVE";

    private final Map<String, Vehicle> vehicles;
    private final Map<String, Driver> drivers;
    private final Map<String, CoverageProfile> coverageProfiles;

    private LabCatalog(Document document) {
        vehicles = index(document.organizations().stream().flatMap(org -> org.vehicles().stream()).toList(),
                Vehicle::id, "vehicle");
        drivers = index(document.organizations().stream().flatMap(org -> org.drivers().stream()).toList(),
                Driver::id, "driver");
        coverageProfiles = index(document.coverageProfiles(), CoverageProfile::code, "coverage profile");
        if (!coverageProfiles.containsKey(DEFAULT_COVERAGE_PROFILE)) {
            throw new IllegalStateException(RESOURCE + " has no " + DEFAULT_COVERAGE_PROFILE + " coverage profile");
        }
    }

    static LabCatalog fromClasspath() {
        try (InputStream json = LabCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (json == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            return read(json);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static LabCatalog read(InputStream json) {
        return new LabCatalog(JsonMapper.builder().build().readValue(json, Document.class));
    }

    Optional<Vehicle> vehicle(String id) {
        return Optional.ofNullable(vehicles.get(id));
    }

    Optional<Driver> driver(String id) {
        return Optional.ofNullable(drivers.get(id));
    }

    Optional<CoverageProfile> coverageProfile(String code) {
        return Optional.ofNullable(coverageProfiles.get(code));
    }

    Collection<Vehicle> vehicles() {
        return vehicles.values();
    }

    private static <T> Map<String, T> index(List<T> entries, Function<T, String> id, String kind) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T entry : entries) {
            if (indexed.putIfAbsent(id.apply(entry), entry) != null) {
                throw new IllegalStateException(RESOURCE + " repeats the " + kind + " " + id.apply(entry));
            }
        }
        return Map.copyOf(indexed);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(List<CoverageProfile> coverageProfiles, List<Organization> organizations) {

        Document {
            coverageProfiles = coverageProfiles == null ? List.of() : coverageProfiles;
            organizations = organizations == null ? List.of() : organizations;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Organization(String id, List<Vehicle> vehicles, List<Driver> drivers) {

        Organization {
            vehicles = vehicles == null ? List.of() : vehicles;
            drivers = drivers == null ? List.of() : drivers;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Vehicle(String id, String label, String make, String model, int year, boolean salvage) {
    }

    /** Condutor; {@code licenseDate} nulo é a habilitação ausente do cadastro. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Driver(String id, String label, LocalDate licenseDate) {

        /** Anos completos de habilitação na data; zero sem habilitação no cadastro. */
        int licenseYears(LocalDate today) {
            return licenseDate == null ? 0 : Math.max(0, Period.between(licenseDate, today).getYears());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CoverageProfile(String code, String label, String summary, List<Coverage> coverages) {

        CoverageProfile {
            coverages = coverages == null ? List.of() : List.copyOf(coverages);
        }

        Optional<Coverage> coverage(String coverageCode) {
            return coverages.stream().filter(coverage -> coverage.code().equals(coverageCode)).findFirst();
        }
    }

    /** {@code insuredAmount} é decimal em string, em BRL. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Coverage(String code, String label, String insuredAmount) {
    }
}

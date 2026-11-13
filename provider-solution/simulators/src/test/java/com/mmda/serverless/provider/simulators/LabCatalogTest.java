package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * O catálogo dos simuladores é o {@code contracts/fixtures/catalog.json} do
 * contrato compartilhado (PRD, seção do
 * contrato), lido do classpath, e tem um veículo para cada cenário que os
 * simuladores decidem pelo id (§10).
 */
class LabCatalogTest {

        private static final Path CONTRACT_CATALOG = Path.of("../../contracts/fixtures/catalog.json");

        private final LabCatalog catalog = LabCatalog.fromClasspath();

        @Test
        void packagedCatalogIsTheContractFixtureItself() throws IOException {
                try (InputStream packaged = LabCatalog.class.getClassLoader()
                                .getResourceAsStream(LabCatalog.RESOURCE)) {
                        assertThat(packaged).isNotNull();
                        assertThat(packaged.readAllBytes()).isEqualTo(Files.readAllBytes(CONTRACT_CATALOG));
                }
        }

        @Test
        void everyScenarioVehicleOfTheSimulatorsIsInTheCatalog() {
                List<String> scenarios = new ArrayList<>(List.of(
                                EnrichmentSimulatorController.VEHICLE_FLAKY,
                                EnrichmentSimulatorController.VEHICLE_ENRICHMENT_DOWN,
                                AlphaInsurerSimulatorController.VEHICLE_ALPHA_DECLINES,
                                AlphaInsurerSimulatorController.VEHICLE_ALPHA_DOWN));
                Arrays.stream(BetaInsurerSimulatorController.BetaScenario.values())
                                .map(BetaInsurerSimulatorController.BetaScenario::vehicleRef)
                                .filter(Objects::nonNull)
                                .forEach(scenarios::add);

                assertThat(scenarios).hasSize(10).allSatisfy(id -> assertThat(catalog.vehicle(id)).as(id).isPresent());
        }

        @Test
        void catalogCarriesTheVehiclesOfTheRulesThatTheQuoteAppliesAroundTheSimulators() {
                assertThat(catalog.vehicles()).filteredOn(vehicle -> SyntheticData.isUnknown(vehicle.id()))
                                .extracting(LabCatalog.Vehicle::id)
                                .containsExactly("vehicle-not-found");
                assertThat(catalog.vehicle("vehicle-classic-1998")).get()
                                .extracting(LabCatalog.Vehicle::year).isEqualTo(1998);
                assertThat(catalog.vehicle("vehicle-salvage")).get()
                                .extracting(LabCatalog.Vehicle::salvage).isEqualTo(true);
                assertThat(catalog.driver("driver-no-license")).get()
                                .extracting(LabCatalog.Driver::licenseDate).isNull();
        }

        @Test
        void licenseYearsAreTheCompleteYearsSinceTheLicenseDate() {
                LabCatalog.Driver driver = catalog.driver("driver-acme-ana").orElseThrow();

                assertThat(driver.licenseDate()).isEqualTo(LocalDate.of(2012, 3, 15));
                assertThat(driver.licenseYears(LocalDate.of(2026, 3, 14))).isEqualTo(13);
                assertThat(driver.licenseYears(LocalDate.of(2026, 3, 15))).isEqualTo(14);
                assertThat(catalog.driver("driver-no-license").orElseThrow().licenseYears(LocalDate.of(2026, 3, 15)))
                                .isZero();
        }

        @Test
        void coverageProfilesComeWithTheirCoveragesAndTheDefaultExists() {
                LabCatalog.CoverageProfile thirdParty = catalog.coverageProfile("THIRD_PARTY").orElseThrow();

                assertThat(thirdParty.coverages()).extracting(LabCatalog.Coverage::code)
                                .containsExactly("THIRD_PARTY_PROPERTY", "THIRD_PARTY_BODILY");
                assertThat(thirdParty.coverage("COLLISION")).isEmpty();
                assertThat(catalog.coverageProfile(LabCatalog.DEFAULT_COVERAGE_PROFILE).orElseThrow()
                                .coverage("COLLISION"))
                                .get().extracting(LabCatalog.Coverage::insuredAmount).isEqualTo("90000.00");
                assertThat(catalog.coverageProfile("PLATINUM")).isEmpty();
        }

        @Test
        void idRepeatedAcrossOrganizationsIsRejected() {
                String json = """
                                {"coverageProfiles":[{"code":"COMPREHENSIVE","coverages":[]}],
                                 "organizations":[
                                   {"id":"org-a","vehicles":[{"id":"vehicle-1","year":2020,"salvage":false}]},
                                   {"id":"org-b","vehicles":[{"id":"vehicle-1","year":2021,"salvage":false}]}]}""";

                assertThatThrownBy(
                                () -> LabCatalog.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("vehicle vehicle-1");
        }

        @Test
        void driverOrCoverageProfileRepeatedIsRejected() {
                String drivers = """
                                {"coverageProfiles":[{"code":"COMPREHENSIVE","coverages":[]}],
                                 "organizations":[
                                   {"id":"org-a","drivers":[{"id":"driver-1","licenseDate":"2012-03-15"}]},
                                   {"id":"org-b","drivers":[{"id":"driver-1","licenseDate":"2015-11-20"}]}]}""";
                String profiles = """
                                {"coverageProfiles":[{"code":"COMPREHENSIVE","coverages":[]},{"code":"COMPREHENSIVE","coverages":[]}],
                                 "organizations":[]}""";

                assertThatThrownBy(() -> LabCatalog
                                .read(new ByteArrayInputStream(drivers.getBytes(StandardCharsets.UTF_8))))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("driver driver-1");
                assertThatThrownBy(() -> LabCatalog
                                .read(new ByteArrayInputStream(profiles.getBytes(StandardCharsets.UTF_8))))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("coverage profile COMPREHENSIVE");
        }

        @Test
        void catalogWithoutTheDefaultCoverageProfileIsRejected() {
                String json = """
                                {"coverageProfiles":[{"code":"THIRD_PARTY","coverages":[]}],
                                 "organizations":[{"id":"org-a","vehicles":[{"id":"vehicle-1","year":2020,"salvage":false}]}]}""";

                assertThatThrownBy(
                                () -> LabCatalog.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("has no COMPREHENSIVE");
        }
}

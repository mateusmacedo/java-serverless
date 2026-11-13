package com.mmda.serverless.provider.simulators;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Cenários do provedor de enriquecimento simulado (desenho, §10). */
@WebMvcTest(EnrichmentSimulatorController.class)
@Import(SimulatorsConfiguration.class)
class EnrichmentSimulatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScenarioCounters counters;

    @Test
    void knownReferencesReturnADeterministicProfile() throws Exception {
        profile("q_1", "vehicle-hatch-2019", "driver-ana")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientReference").value("q_1"))
                .andExpect(jsonPath("$.vehicle.ref").value("vehicle-hatch-2019"))
                .andExpect(jsonPath("$.vehicle.modelYear").value(2019))
                .andExpect(jsonPath("$.vehicle.marketValue").isString())
                .andExpect(jsonPath("$.driver.ref").value("driver-ana"))
                .andExpect(jsonPath("$.observedAt").isString());
        profile("q_2", "vehicle-hatch-2019", "driver-ana")
                .andExpect(jsonPath("$.vehicle.make").value(SyntheticData.vehicle("vehicle-hatch-2019").make()))
                .andExpect(jsonPath("$.driver.licenseYears").value(SyntheticData.driver("driver-ana").licenseYears()));
        verify(counters, never()).increment(anyString());
    }

    @Test
    void oldVehicleKeepsTheYearOfItsReference() throws Exception {
        profile("q_1", "vehicle-classic-1995", "driver-ana")
                .andExpect(jsonPath("$.vehicle.modelYear").value(1995));
    }

    @Test
    void unknownVehicleIsADeclaredNotFound() throws Exception {
        profile("q_1", "vehicle-unknown", "driver-ana")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.notFoundRefs[0]").value("vehicleRef"))
                .andExpect(jsonPath("$.notFoundRefs.length()").value(1));
    }

    @Test
    void enrichmentDownAlwaysTimesOutUpstream() throws Exception {
        profile("q_1", "vehicle-enrichment-down", "driver-ana")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));
    }

    @Test
    void flakyVehicleFailsTheFirstTwoCallsOfEachReference() throws Exception {
        when(counters.increment("FLAKY#q_1")).thenReturn(1L, 2L, 3L);

        profile("q_1", "vehicle-flaky", "driver-ana").andExpect(status().isServiceUnavailable());
        profile("q_1", "vehicle-flaky", "driver-ana").andExpect(status().isServiceUnavailable());
        profile("q_1", "vehicle-flaky", "driver-ana").andExpect(status().isOk());
    }

    @Test
    void incompleteRequestIsRejected() throws Exception {
        mockMvc.perform(post("/sim/enrichment/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientReference\":\"q_1\",\"vehicleRef\":\"vehicle-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private ResultActions profile(String clientReference, String vehicleRef, String driverRef) throws Exception {
        return mockMvc.perform(post("/sim/enrichment/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientReference":"%s","vehicleRef":"%s","principalDriverRef":"%s"}"""
                        .formatted(clientReference, vehicleRef, driverRef)));
    }
}

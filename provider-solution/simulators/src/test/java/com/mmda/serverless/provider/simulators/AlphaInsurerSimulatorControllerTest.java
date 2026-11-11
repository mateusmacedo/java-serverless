package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Seguradora síncrona alpha simulada (desenho, §10). */
@WebMvcTest(AlphaInsurerSimulatorController.class)
@Import(SimulatorsConfiguration.class)
@EnableConfigurationProperties(SimulatorProperties.class)
@TestPropertySource(properties = {"SIMULATORS_TABLE_NAME=simulators", "OFFER_VALIDITY_SECONDS=1800"})
class AlphaInsurerSimulatorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void quotesTheRiskWithAnOfferValidForTheConfiguredTime() throws Exception {
        String body = quote("q_1", "{\"vehicle\":{\"ref\":\"vehicle-hatch-2019\"},\"usage\":\"PERSONAL\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OFFER"))
                .andExpect(jsonPath("$.clientReference").value("q_1"))
                .andExpect(jsonPath("$.offerNumber").value("ALPHA-" + SyntheticData.shortCode("q_1")))
                .andExpect(jsonPath("$.insurerName").value("Alpha Seguros"))
                .andExpect(jsonPath("$.premium.currency").value("BRL"))
                .andExpect(jsonPath("$.paymentOptions[1].installments").value(10))
                .andExpect(jsonPath("$.coverages[0].code").value("COLLISION"))
                .andExpect(jsonPath("$.deductibles[0].coverage").value("COLLISION"))
                .andReturn().getResponse().getContentAsString();

        JsonNode offer = objectMapper.readTree(body);
        assertThat(offer.path("premium").path("total").isString()).isTrue();
        assertThat(new BigDecimal(offer.path("premium").path("total").asString()))
                .isEqualByComparingTo(SyntheticData.basePremium("vehicle-hatch-2019", "PERSONAL"));
        assertThat(java.time.Instant.parse(offer.path("expiresAt").asString()))
                .isAfter(java.time.Instant.now().plusSeconds(1700));
    }

    @Test
    void commercialUseCostsMore() {
        assertThat(SyntheticData.basePremium("vehicle-van-2020", "COMMERCIAL"))
                .isGreaterThan(SyntheticData.basePremium("vehicle-van-2020", "PERSONAL"));
    }

    @Test
    void declinesTheVehicleOfTheDeclineScenario() throws Exception {
        quote("q_1", "{\"vehicle\":{\"ref\":\"vehicle-alpha-declines\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINED"))
                .andExpect(jsonPath("$.declineCode").value("RISK_NOT_ACCEPTED"));
    }

    @Test
    void isUnavailableForTheVehicleOfTheDownScenario() throws Exception {
        quote("q_1", "{\"vehicle\":{\"id\":\"vehicle-alpha-down\"}}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"));
    }

    @Test
    void acceptsTheVehicleAsAReferenceOnly() throws Exception {
        quote("q_1", "{\"vehicleRef\":\"vehicle-alpha-declines\"}")
                .andExpect(jsonPath("$.outcome").value("DECLINED"));
    }

    @Test
    void rejectsARiskWithoutVehicle() throws Exception {
        quote("q_1", "{\"usage\":\"PERSONAL\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private ResultActions quote(String clientReference, String risk) throws Exception {
        return mockMvc.perform(post("/sim/insurers/alpha/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientReference\":\"%s\",\"risk\":%s,\"enrichment\":{}}".formatted(clientReference, risk)));
    }
}

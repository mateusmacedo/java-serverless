package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import org.hamcrest.Matchers;
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
@TestPropertySource(properties = { "SIMULATORS_TABLE_NAME=simulators", "OFFER_VALIDITY_SECONDS=1800" })
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
        void coveragesAreTheOnesOfTheRequestedProfileInTheCatalog() throws Exception {
                quote("q_1", """
                                {"vehicle":{"id":"vehicle-hatch-2021","make":"Fiat","model":"Argo","year":2021,"salvage":false},\
                                "usage":"PERSONAL","coverageProfile":{"code":"COMPREHENSIVE","label":"Completa"}}""")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.coverages.length()").value(4))
                                .andExpect(jsonPath("$.coverages[0].code").value("COLLISION"))
                                .andExpect(jsonPath("$.coverages[0].description").value("Colisão"))
                                .andExpect(jsonPath("$.coverages[0].limit").value("90000.00"))
                                .andExpect(jsonPath("$.coverages[1].code").value("FIRE_THEFT"))
                                .andExpect(jsonPath("$.coverages[3].limit").value("100000.00"))
                                .andExpect(jsonPath("$.deductibles.length()").value(1))
                                .andExpect(jsonPath("$.deductibles[0].amount").value("4500.00"));
                quote("q_2", "{\"vehicle\":{\"id\":\"vehicle-hatch-2021\"},\"coverageProfile\":\"THIRD_PARTY\"}")
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.coverages[*].code").value(
                                                Matchers.contains("THIRD_PARTY_PROPERTY", "THIRD_PARTY_BODILY")))
                                .andExpect(jsonPath("$.coverages[0].limit").value("50000.00"))
                                .andExpect(jsonPath("$.deductibles").isEmpty());
        }

        @Test
        void coverageProfileOutsideTheCatalogIsAnInvalidRequest() throws Exception {
                quote("q_1", "{\"vehicle\":{\"id\":\"vehicle-hatch-2021\"},\"coverageProfile\":{\"code\":\"PLATINUM\"}}")
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
                                .content("{\"clientReference\":\"%s\",\"risk\":%s,\"enrichment\":{}}"
                                                .formatted(clientReference, risk)));
        }
}

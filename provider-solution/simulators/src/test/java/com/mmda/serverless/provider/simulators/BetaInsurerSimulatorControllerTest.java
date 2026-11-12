package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Seguradora assíncrona beta simulada e os cenários pelo veículo (desenho, §10). */
@WebMvcTest(BetaInsurerSimulatorController.class)
@Import(SimulatorsConfiguration.class)
@EnableConfigurationProperties(SimulatorProperties.class)
@TestPropertySource(properties = {"SIMULATORS_TABLE_NAME=simulators", "BETA_CALLBACK_DELAY_SECONDS=3",
        "BETA_LATE_CALLBACK_DELAY_SECONDS=30"})
class BetaInsurerSimulatorControllerTest {

    private static final String CLIENT_REFERENCE = "q_0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BetaQuotes quotes;

    @MockitoBean
    private ScheduledCallbacks callbacks;

    @Test
    void acceptsWithAProviderRequestIdAndSchedulesTheOfferWithTheClientReference() throws Exception {
        quote("vehicle-hatch-2021")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.clientReference").value(CLIENT_REFERENCE))
                .andExpect(jsonPath("$.providerRequestId").value(startsWith("beta-")))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        List<Scheduled> scheduled = scheduled(1);
        assertThat(scheduled.getFirst().delaySeconds()).isEqualTo(3);
        CallbackOrder order = scheduled.getFirst().order();
        assertThat(order.provider()).isEqualTo("beta");
        assertThat(order.clientReference()).isEqualTo(CLIENT_REFERENCE);
        assertThat(order.externalEventId()).startsWith("bev-");
        assertThat(order.result()).containsEntry("outcome", "OFFER")
                .containsEntry("insurerName", "Beta Seguros")
                .containsEntry("offerNumber", "BETA-" + SyntheticData.shortCode(CLIENT_REFERENCE));
        BetaQuote saved = saved();
        assertThat(saved.providerRequestId()).isEqualTo(order.providerRequestId());
        assertThat(saved.scenario()).isEqualTo("OFFER");
        assertThat(saved.readyAt()).isEqualTo(saved.receivedAt().plusSeconds(3));
        assertThat(saved.result()).isEqualTo(order.result());
    }

    @Test
    void declinesAndPendingInformationAreDeliveredByCallback() throws Exception {
        quote("vehicle-beta-declines").andExpect(status().isAccepted());
        quote("vehicle-beta-needs-info").andExpect(status().isAccepted());

        List<Scheduled> scheduled = scheduled(2);
        assertThat(scheduled.get(0).order().result())
                .isEqualTo(Map.of("outcome", "DECLINED", "declineCode", "RISK_NOT_ACCEPTED"));
        assertThat(scheduled.get(1).order().result()).containsEntry("outcome", "PENDING_INFORMATION")
                .containsEntry("pendingFields", List.of(Map.of(
                        "field", "risk.principalDriver.licenseDate", "code", "MISSING_LICENSE_DATE")));
    }

    @Test
    void silentScenarioNeverCallsBackButTheSituationIsKept() throws Exception {
        quote("vehicle-beta-silent").andExpect(status().isAccepted());

        verify(callbacks, never()).schedule(any(), anyInt());
        assertThat(saved().scenario()).isEqualTo("SILENT");
    }

    @Test
    void duplicateScenarioSendsTheSameEventTwice() throws Exception {
        quote("vehicle-beta-duplicate").andExpect(status().isAccepted());

        List<Scheduled> scheduled = scheduled(2);
        assertThat(scheduled).extracting(Scheduled::delaySeconds)
                .containsExactly(3, 3 + BetaInsurerSimulatorController.DUPLICATE_GAP_SECONDS);
        assertThat(scheduled.get(1).order()).isEqualTo(scheduled.get(0).order());
    }

    @Test
    void lateScenarioCallsBackAfterTheInsurerWait() throws Exception {
        quote("vehicle-beta-late").andExpect(status().isAccepted());

        assertThat(scheduled(1).getFirst().delaySeconds()).isEqualTo(30);
    }

    @Test
    void earlyScenarioCallsBackRightAwayWithOnlyTheProviderRequestId() throws Exception {
        quote("vehicle-beta-early").andExpect(status().isAccepted());

        Scheduled early = scheduled(1).getFirst();
        assertThat(early.delaySeconds()).isZero();
        assertThat(early.order().clientReference()).isNull();
        assertThat(early.order().providerRequestId()).startsWith("beta-");
    }

    @Test
    void situationIsProcessingUntilTheResultIsReadyAndUnknownReferencesAreNotFound() throws Exception {
        Instant now = Instant.now();
        BetaQuote ready = new BetaQuote("beta-1", CLIENT_REFERENCE, "DECLINES", now.minusSeconds(10),
                now.minusSeconds(7), Map.of("outcome", "DECLINED", "declineCode", "RISK_NOT_ACCEPTED"));
        BetaQuote pending = new BetaQuote("beta-2", CLIENT_REFERENCE, "LATE", now, now.plusSeconds(30),
                Map.of("outcome", "DECLINED"));
        when(quotes.find("beta-1")).thenReturn(Optional.of(ready));
        when(quotes.find(CLIENT_REFERENCE)).thenReturn(Optional.of(pending));
        when(quotes.find("beta-unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/sim/insurers/beta/quotes/beta-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").value(ready.readyAt().toString()))
                .andExpect(jsonPath("$.result.declineCode").value("RISK_NOT_ACCEPTED"));
        mockMvc.perform(get("/sim/insurers/beta/quotes/" + CLIENT_REFERENCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerRequestId").value("beta-2"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.result").doesNotExist());
        mockMvc.perform(get("/sim/insurers/beta/quotes/beta-unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void requestWithoutClientReferenceIsRejected() throws Exception {
        mockMvc.perform(post("/sim/insurers/beta/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"risk\":{\"vehicle\":{\"ref\":\"vehicle-hatch-2021\"}}}"))
                .andExpect(status().isBadRequest());

        verify(callbacks, never()).schedule(any(), anyInt());
    }

    private ResultActions quote(String vehicleRef) throws Exception {
        return mockMvc.perform(post("/sim/insurers/beta/quotes").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientReference":"%s","risk":{"usage":"PERSONAL","vehicle":{"ref":"%s"}},"enrichment":{}}"""
                        .formatted(CLIENT_REFERENCE, vehicleRef)));
    }

    private List<Scheduled> scheduled(int times) {
        ArgumentCaptor<CallbackOrder> orders = ArgumentCaptor.forClass(CallbackOrder.class);
        ArgumentCaptor<Integer> delays = ArgumentCaptor.forClass(Integer.class);
        verify(callbacks, times(times)).schedule(orders.capture(), delays.capture());
        return IntStream.range(0, times)
                .mapToObj(index -> new Scheduled(orders.getAllValues().get(index), delays.getAllValues().get(index)))
                .toList();
    }

    private BetaQuote saved() {
        ArgumentCaptor<BetaQuote> saved = ArgumentCaptor.forClass(BetaQuote.class);
        verify(quotes).save(saved.capture());
        return saved.getValue();
    }

    private record Scheduled(CallbackOrder order, int delaySeconds) {
    }
}

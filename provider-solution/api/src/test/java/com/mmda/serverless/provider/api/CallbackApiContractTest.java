package com.mmda.serverless.provider.api;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.mmda.serverless.provider.domain.insurer.CallbackSignature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Confere requisições e respostas do controller contra {@code docs/api/provider-solution.openapi.yaml}. O base
 * path é zerado porque os servidores do contrato incluem o prefixo do API Gateway, que não existe no MockMvc.
 */
@WebMvcTest(CallbackController.class)
@EnableConfigurationProperties(ApiProperties.class)
@TestPropertySource(properties = {"TABLE_NAME=queries", "PAYLOADS_BUCKET=payloads", "CALLBACK_SECRET_ID=secret"})
@ExtendWith(OutputCaptureExtension.class)
class CallbackApiContractTest {

    private static final String SPECIFICATION =
            Path.of("../../docs/api/provider-solution.openapi.yaml").toAbsolutePath().toUri().toString();
    private static final byte[] SECRET = "contract-secret".getBytes(StandardCharsets.UTF_8);
    private static final String CALLBACK = CallbackFixtures.callback("bev-1", "beta-1",
            "q_0123456789abcdef0123456789abcdef", CallbackFixtures.OFFER);

    private static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .build();

    // WHY: nos casos de erro a requisição é inválida de propósito; só a resposta é conferida
    private static final OpenApiInteractionValidator RESPONSES_ONLY = OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build())
            .build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CallbackSecret secret;

    @MockitoBean
    private CallbackInbox inbox;

    @BeforeEach
    void secret() {
        when(secret.value()).thenReturn(SECRET);
    }

    @Test
    void processedCallbackFollowsTheContract() throws Exception {
        when(inbox.receive(eq("beta"), any(), anyString()))
                .thenReturn(new CallbackInbox.Receipt("bev-1", "PROCESSED", false));

        send(CALLBACK, signature(CALLBACK)).andExpect(status().isOk()).andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void callbackHeldInTheInboxFollowsTheContract() throws Exception {
        when(inbox.receive(eq("beta"), any(), anyString()))
                .thenReturn(new CallbackInbox.Receipt("bev-1", "RECEIVED", true));

        send(CALLBACK, signature(CALLBACK)).andExpect(status().isAccepted()).andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void wrongSignatureIsDeclaredInTheContractAndNeverReachesTheInbox() throws Exception {
        send(CALLBACK, "sha256=" + "0".repeat(64))
                .andExpect(status().isUnauthorized())
                .andExpect(openApi().isValid(CONTRACT));

        verify(inbox, never()).receive(anyString(), any(), anyString());
    }

    @Test
    void invalidCallbacksAndUnknownProvidersAreDeclaredInTheContract() throws Exception {
        String withoutResult = "{\"externalEventId\":\"bev-1\",\"providerRequestId\":\"beta-1\"}";

        send(withoutResult, signature(withoutResult))
                .andExpect(status().isBadRequest())
                .andExpect(openApi().isValid(RESPONSES_ONLY));
        mockMvc.perform(post("/v1/providers/gamma/callbacks").contentType(MediaType.APPLICATION_JSON)
                        .content(CALLBACK).header(CallbackSignature.HEADER, signature(CALLBACK)))
                .andExpect(status().isNotFound())
                .andExpect(openApi().isValid(RESPONSES_ONLY));
        mockMvc.perform(post("/v1/providers/beta/callbacks").contentType(MediaType.TEXT_PLAIN).content("oi")
                        .header(CallbackSignature.HEADER, signature("oi")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(openApi().isValid(RESPONSES_ONLY));
    }

    @Test
    void resultOutsideItsSchemaStillReachesTheInbox() throws Exception {
        when(inbox.receive(eq("beta"), any(), anyString()))
                .thenReturn(new CallbackInbox.Receipt("bev-1", "PROCESSED", false));
        String wrongTypes = CallbackFixtures.callback("bev-1", "beta-1", "q_0123456789abcdef0123456789abcdef",
                "{\"outcome\":\"OFFER\",\"premium\":\"2481.46\",\"paymentOptions\":{\"installments\":\"doze\"}}");
        String notAnObject = CallbackFixtures.callback("bev-2", "beta-2", null, "\"OFFER\"");

        send(wrongTypes, signature(wrongTypes)).andExpect(status().isOk()).andExpect(openApi().isValid(RESPONSES_ONLY));
        send(notAnObject, signature(notAnObject)).andExpect(status().isOk());

        verify(inbox).receive(eq("beta"), eq(new CallbackEnvelope("bev-1", "beta-1",
                "q_0123456789abcdef0123456789abcdef")), eq(wrongTypes));
        verify(inbox).receive(eq("beta"), eq(new CallbackEnvelope("bev-2", "beta-2", null)), eq(notAnObject));
    }

    @Test
    void oversizedCallbackIsRefusedForGoodWithoutReachingTheInbox() throws Exception {
        String oversized = CallbackFixtures.callback("bev-1", "beta-1", null,
                "{\"outcome\":\"DECLINED\",\"declineCode\":\"" + "X".repeat(CallbackController.MAX_BODY_BYTES) + "\"}");

        send(oversized, signature(oversized))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("CALLBACK_TOO_LARGE"))
                .andExpect(openApi().isValid(RESPONSES_ONLY));

        verify(inbox, never()).receive(anyString(), any(), anyString());
    }

    @Test
    void unsignedRequestCannotWriteItsPathIntoTheLog(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/v1/providers/{provider}/callbacks", "beta\nINFO Callback processed: forged")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CALLBACK)
                        .header(CallbackSignature.HEADER, "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized());

        assertThat(output.getAll()).contains("Callback rejected by its signature").doesNotContain("forged");
    }

    private ResultActions send(String body, String signature) throws Exception {
        return mockMvc.perform(post("/v1/providers/beta/callbacks").contentType(MediaType.APPLICATION_JSON)
                .content(body).header(CallbackSignature.HEADER, signature));
    }

    private static String signature(String body) {
        return CallbackSignature.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }
}

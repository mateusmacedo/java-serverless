package com.mmda.serverless.sample.api;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Confere requisições e respostas do controller contra o contrato publicado em
 * contracts/sample-solution/openapi.yaml. O base path é zerado porque os servidores do contrato incluem o prefixo
 * do API Gateway, que não existe no MockMvc.
 */
@WebMvcTest(RequestController.class)
@EnableConfigurationProperties(ApiProperties.class)
@TestPropertySource(properties = {
        "BUCKET_NAME=payloads-bucket",
        "STATE_MACHINE_ARN=arn:aws:states:us-east-1:000000000000:stateMachine:request-flow"
})
class RequestControllerContractTest {

    private static final String SPECIFICATION =
            Path.of("../../contracts/sample-solution/openapi.yaml").toAbsolutePath().toUri().toString();

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
    private S3Client s3;

    @MockitoBean
    private SfnClient sfn;

    @Test
    void acceptedRequestAndResponseFollowTheContract() throws Exception {
        AwsClientStubs.givenS3AcceptsUploads(s3);
        AwsClientStubs.givenExecutionStarts(sfn);

        mockMvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content("{\"customer\":\"acme\"}"))
                .andExpect(status().isAccepted())
                .andExpect(openApi().isValid(CONTRACT));
    }

    @Test
    void unsupportedMediaTypeIsDeclaredInTheContract() throws Exception {
        mockMvc.perform(post("/requests").contentType(MediaType.TEXT_PLAIN).content("oi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(openApi().isValid(RESPONSES_ONLY));
    }

    @Test
    void missingBodyIsDeclaredInTheContract() throws Exception {
        mockMvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(openApi().isValid(RESPONSES_ONLY));
    }
}

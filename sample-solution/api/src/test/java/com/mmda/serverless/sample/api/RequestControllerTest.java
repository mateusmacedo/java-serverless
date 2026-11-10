package com.mmda.serverless.sample.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

@WebMvcTest(RequestController.class)
@EnableConfigurationProperties(ApiProperties.class)
@TestPropertySource(properties = {
        "BUCKET_NAME=payloads-bucket",
        "STATE_MACHINE_ARN=arn:aws:states:us-east-1:000000000000:stateMachine:request-flow"
})
class RequestControllerTest {

    private static final String PAYLOAD = "{\"customer\":\"acme\",\"amount\":42}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private S3Client s3;

    @MockitoBean
    private SfnClient sfn;

    @Test
    @SuppressWarnings("unchecked")
    void storesThePayloadAndStartsTheExecution() throws Exception {
        AwsClientStubs.givenS3AcceptsUploads(s3);
        AwsClientStubs.givenExecutionStarts(sfn);

        String response = mockMvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionArn").value(AwsClientStubs.EXECUTION_ARN))
                .andReturn().getResponse().getContentAsString();
        String requestId = JsonPath.read(response, "$.requestId");
        assertThat(UUID.fromString(requestId)).hasToString(requestId);

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> put = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(put.capture(), body.capture());
        PutObjectRequest upload = AwsClientStubs.build(put.getValue());
        assertThat(upload.bucket()).isEqualTo("payloads-bucket");
        assertThat(upload.key()).isEqualTo("requests/" + requestId + ".json");
        assertThat(upload.contentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        try (InputStream content = body.getValue().contentStreamProvider().newStream()) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        }

        ArgumentCaptor<Consumer<StartExecutionRequest.Builder>> start = ArgumentCaptor.forClass(Consumer.class);
        verify(sfn).startExecution(start.capture());
        StartExecutionRequest execution = AwsClientStubs.buildStart(start.getValue());
        assertThat(execution.stateMachineArn())
                .isEqualTo("arn:aws:states:us-east-1:000000000000:stateMachine:request-flow");
        assertThat(execution.name()).isEqualTo(requestId);
        JsonNode input = objectMapper.readTree(execution.input());
        assertThat(input.get("requestId").asText()).isEqualTo(requestId);
        assertThat(input.get("s3Key").asText()).isEqualTo("requests/" + requestId + ".json");
        assertThat(input.get("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsAContentTypeOtherThanJson() throws Exception {
        mockMvc.perform(post("/requests").contentType(MediaType.TEXT_PLAIN).content("oi"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(s3, sfn);
    }

    @Test
    void rejectsAMissingBody() throws Exception {
        mockMvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(s3, never()).putObject(AwsClientStubs.<PutObjectRequest.Builder>anyConsumer(), any(RequestBody.class));
        verifyNoInteractions(sfn);
    }
}

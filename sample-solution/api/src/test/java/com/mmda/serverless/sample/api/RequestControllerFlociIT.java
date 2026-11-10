package com.mmda.serverless.sample.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;

/** A aplicação HTTP completa, com os clients reais da autoconfiguração, contra o Floci. */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class RequestControllerFlociIT extends FlociIntegrationTest {

    private static final String BUCKET = "it-api-payloads";
    private static final S3Client S3 = S3Client.builder().forcePathStyle(true).build();
    private static final SfnClient SFN = SfnClient.create();
    private static final String STATE_MACHINE_ARN;

    static {
        S3.createBucket(builder -> builder.bucket(BUCKET));
        STATE_MACHINE_ARN = SFN.createStateMachine(builder -> builder
                .name("it-api-request-flow")
                .roleArn("arn:aws:iam::000000000000:role/it-api-request-flow")
                .definition("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}"))
                .stateMachineArn();
    }

    @DynamicPropertySource
    static void sampleProperties(DynamicPropertyRegistry registry) {
        registry.add("BUCKET_NAME", () -> BUCKET);
        registry.add("STATE_MACHINE_ARN", () -> STATE_MACHINE_ARN);
        registry.add("S3_PATH_STYLE_ACCESS", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void storesThePayloadAndStartsARealExecution() throws Exception {
        String payload = "{\"customer\":\"acme\",\"amount\":42}";

        String response = mockMvc.perform(post("/requests").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode accepted = objectMapper.readTree(response);
        String requestId = accepted.get("requestId").asText();

        String stored = S3.getObjectAsBytes(builder -> builder.bucket(BUCKET).key("requests/" + requestId + ".json"))
                .asUtf8String();
        assertThat(stored).isEqualTo(payload);

        DescribeExecutionResponse execution = SFN.describeExecution(builder -> builder
                .executionArn(accepted.get("executionArn").asText()));
        assertThat(execution.name()).isEqualTo(requestId);
        assertThat(execution.stateMachineArn()).isEqualTo(STATE_MACHINE_ARN);
        JsonNode input = objectMapper.readTree(execution.input());
        assertThat(input.get("s3Key").asText()).isEqualTo("requests/" + requestId + ".json");
        assertThat(input.get("attempt").asInt()).isEqualTo(1);
    }
}

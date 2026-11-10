package com.mmda.serverless.sample.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Grava o payload no S3 e inicia a máquina de estado só com a chave, porque o Step Functions
 * limita a entrada de cada estado a 256 KB.
 */
@RestController
@RequestMapping("/requests")
class RequestController {

    private final S3Client s3;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final ApiProperties properties;

    RequestController(S3Client s3, SfnClient sfn, ObjectMapper objectMapper, ApiProperties properties) {
        this.s3 = s3;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RequestAccepted> create(@RequestBody String payload) throws JsonProcessingException {
        String requestId = UUID.randomUUID().toString();
        String s3Key = "requests/" + requestId + ".json";

        s3.putObject(
                builder -> builder.bucket(properties.bucketName()).key(s3Key).contentType(MediaType.APPLICATION_JSON_VALUE),
                software.amazon.awssdk.core.sync.RequestBody.fromString(payload));

        String input = objectMapper.writeValueAsString(new ExecutionInput(requestId, s3Key, 1));
        StartExecutionResponse execution = sfn.startExecution(builder -> builder
                .stateMachineArn(properties.stateMachineArn())
                .name(requestId)
                .input(input));

        return ResponseEntity.accepted().body(new RequestAccepted(requestId, execution.executionArn()));
    }

    record ExecutionInput(String requestId, String s3Key, int attempt) {
    }

    record RequestAccepted(String requestId, String executionArn) {
    }
}

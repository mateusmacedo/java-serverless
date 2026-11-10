package com.mmda.serverless.sample.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/** Stubs dos clients do SDK nas sobrecargas com {@link Consumer}, que o controller usa. */
final class AwsClientStubs {

    static final String EXECUTION_ARN =
            "arn:aws:states:us-east-1:000000000000:execution:request-flow:any";

    private AwsClientStubs() {
    }

    static void givenS3AcceptsUploads(S3Client s3) {
        when(s3.putObject(AwsClientStubs.<PutObjectRequest.Builder>anyConsumer(), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
    }

    static void givenExecutionStarts(SfnClient sfn) {
        when(sfn.startExecution(AwsClientStubs.<StartExecutionRequest.Builder>anyConsumer()))
                .thenReturn(StartExecutionResponse.builder().executionArn(EXECUTION_ARN).build());
    }

    static <B> Consumer<B> anyConsumer() {
        return any();
    }

    static PutObjectRequest build(Consumer<PutObjectRequest.Builder> consumer) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        consumer.accept(builder);
        return builder.build();
    }

    static StartExecutionRequest buildStart(Consumer<StartExecutionRequest.Builder> consumer) {
        StartExecutionRequest.Builder builder = StartExecutionRequest.builder();
        consumer.accept(builder);
        return builder.build();
    }
}

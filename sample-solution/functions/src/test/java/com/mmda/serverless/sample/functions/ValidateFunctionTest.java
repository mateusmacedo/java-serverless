package com.mmda.serverless.sample.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

@ExtendWith(MockitoExtension.class)
// WHY: a sobrecarga do SDK com Consumer repassa para a sobrecarga com request, que é a simulada;
// no modo estrito o Mockito trata essa chamada como argumento divergente
@MockitoSettings(strictness = Strictness.LENIENT)
class ValidateFunctionTest {

    // Vetor de teste conhecido do HMAC-SHA256 (chave "key")
    private static final String SIGNING_KEY = "key";
    private static final String PAYLOAD = "The quick brown fox jumps over the lazy dog";
    private static final String EXPECTED_SIGNATURE = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private S3Client s3;

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private SsmClient ssm;

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private SecretsManagerClient secretsManager;

    private ValidateFunction function;

    @BeforeEach
    void setUp() {
        function = new ValidateFunction(s3, ssm, secretsManager, TestFixtures.PROPERTIES);
        doReturn(GetSecretValueResponse.builder().secretString(SIGNING_KEY).build())
                .when(secretsManager).getSecretValue(any(GetSecretValueRequest.class));
    }

    @Test
    void signsThePayloadWithTheKeyFromSecretsManager() {
        givenLimit(4096);
        givenPayload(PAYLOAD);

        RequestContext result = function.apply(TestFixtures.newRequest(1));

        assertThat(result.signature()).isEqualTo(EXPECTED_SIGNATURE);
        assertThat(result.payloadBytes()).isEqualTo(PAYLOAD.length());
        assertThat(result.requestId()).isEqualTo("req-1");
    }

    @Test
    void readsTheConfiguredParameterSecretAndObject() {
        givenLimit(4096);
        givenPayload(PAYLOAD);

        function.apply(TestFixtures.newRequest(1));

        ArgumentCaptor<GetParameterRequest> parameter = ArgumentCaptor.forClass(GetParameterRequest.class);
        verify(ssm).getParameter(parameter.capture());
        assertThat(parameter.getValue().name()).isEqualTo("/sample/max-payload-bytes");

        ArgumentCaptor<GetSecretValueRequest> secret = ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(secretsManager).getSecretValue(secret.capture());
        assertThat(secret.getValue().secretId()).isEqualTo("sample/signing-key");

        ArgumentCaptor<GetObjectRequest> object = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(object.capture());
        assertThat(object.getValue().bucket()).isEqualTo("payloads-bucket");
        assertThat(object.getValue().key()).isEqualTo("requests/req-1.json");
    }

    @Test
    void acceptsAPayloadExactlyAtTheLimit() {
        givenLimit(PAYLOAD.length());
        givenPayload(PAYLOAD);

        assertThat(function.apply(TestFixtures.newRequest(1)).payloadBytes()).isEqualTo(PAYLOAD.length());
    }

    @Test
    void rejectsAPayloadAboveTheLimit() {
        givenLimit(10);
        givenPayload(PAYLOAD);

        assertThatThrownBy(() -> function.apply(TestFixtures.newRequest(1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Payload of 43 bytes exceeds the limit of 10 bytes");
    }

    @Test
    void rejectsAnEmptyPayload() {
        givenLimit(4096);
        givenPayload("");

        assertThatThrownBy(() -> function.apply(TestFixtures.newRequest(1)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Payload of request req-1 is empty");
    }

    private void givenLimit(long bytes) {
        doReturn(GetParameterResponse.builder()
                .parameter(Parameter.builder().value(Long.toString(bytes)).build())
                .build())
                .when(ssm).getParameter(any(GetParameterRequest.class));
    }

    private void givenPayload(String content) {
        doReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                content.getBytes(StandardCharsets.UTF_8)))
                .when(s3).getObjectAsBytes(any(GetObjectRequest.class));
    }
}

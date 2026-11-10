package com.mmda.serverless.sample.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

@ExtendWith(MockitoExtension.class)
// WHY: a sobrecarga do SDK com Consumer repassa para a sobrecarga com request, que é a simulada;
// no modo estrito o Mockito trata essa chamada como argumento divergente
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistFunctionTest {

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private DynamoDbClient dynamoDb;

    @Test
    void writesTheValidatedRequestToTheTable() {
        doReturn(PutItemResponse.builder().build()).when(dynamoDb).putItem(any(PutItemRequest.class));
        RequestContext request = TestFixtures.validatedRequest();

        RequestContext result = new PersistFunction(dynamoDb, TestFixtures.PROPERTIES).apply(request);

        assertThat(result).isEqualTo(request);
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        PutItemRequest put = captor.getValue();
        assertThat(put.tableName()).isEqualTo("requests-table");

        Map<String, AttributeValue> item = put.item();
        assertThat(item.get("requestId").s()).isEqualTo("req-1");
        assertThat(item.get("s3Key").s()).isEqualTo("requests/req-1.json");
        assertThat(item.get("signature").s()).isEqualTo("abc123");
        assertThat(item.get("payloadBytes").n()).isEqualTo("42");
        assertThat(item.get("attempt").n()).isEqualTo("1");
        assertThat(Instant.parse(item.get("persistedAt").s())).isBeforeOrEqualTo(Instant.now());
    }
}

package com.mmda.serverless.provider.api;

import java.time.Clock;
import java.util.List;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

/**
 * A metade da API de callbacks com o código do {@code provider-api}, sem a camada HTTP: o que o controller faz
 * depois de conferir a assinatura e o envelope. Os testes do worker gravam e processam {@code INBOX#} e
 * {@code CORR#} por aqui, então uma mudança de formato num dos lados quebra o encontro nos testes.
 */
public final class CallbackApi {

    private final CallbackInbox inbox;
    private final ObjectMapper objectMapper;

    public CallbackApi(DynamoDbClient dynamoDb, S3Client s3, ObjectMapper objectMapper, String tableName,
                       String payloadsBucket) {
        ApiProperties properties = new ApiProperties(tableName, payloadsBucket, null, List.of("beta"));
        CallbackTable table = new CallbackTable(dynamoDb, new Transactions(dynamoDb), properties);
        this.inbox = new CallbackInbox(table, new RawCallbacks(s3, properties), objectMapper, Clock.systemUTC());
        this.objectMapper = objectMapper;
    }

    /** {@code processingStatus} da resposta: {@code PROCESSED} com o resultado gravado; senão, {@code RECEIVED}. */
    public String receive(String provider, String body) {
        CallbackEnvelope envelope = objectMapper.readValue(body, CallbackEnvelope.class);
        List<String> problems = envelope.problems(objectMapper.readTree(body).hasNonNull("result"));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The API would refuse this callback: " + problems);
        }
        return inbox.receive(provider, envelope, body).processingStatus();
    }
}

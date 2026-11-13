package com.mmda.serverless.quote.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Recusas do DynamoDB que a tabela simulada dos testes de contrato reproduz,
 * porque o mock e o Floci não as fazem:
 * chave de partição acima de 2048 bytes, de ordenação acima de 1024 e
 * {@code ExclusiveStartKey} fora da condição da
 * chave respondem {@code ValidationException}.
 */
final class DynamoDbLimits {

    private static final int PARTITION_KEY_BYTES = 2048;
    private static final int SORT_KEY_BYTES = 1024;

    private DynamoDbLimits() {
    }

    static void checkKey(Map<String, AttributeValue> key) {
        if (bytes(key.get("pk").s()) > PARTITION_KEY_BYTES || bytes(key.get("sk").s()) > SORT_KEY_BYTES) {
            throw validation("One or more parameter values were invalid: Size of hashkey has exceeded the maximum "
                    + "size limit of 2048 bytes");
        }
    }

    /**
     * Página do {@code gsi1} com os itens informados, depois das conferências do
     * {@code ExclusiveStartKey}.
     */
    static QueryResponse query(QueryRequest request, List<Map<String, AttributeValue>> items) {
        if (request.hasExclusiveStartKey()) {
            String position = request.exclusiveStartKey().get("gsi1sk").s();
            AttributeValue from = request.expressionAttributeValues().get(":from");
            AttributeValue to = request.expressionAttributeValues().get(":to");
            if (bytes(position) > SORT_KEY_BYTES) {
                throw validation("One or more parameter values were invalid: Aggregated size of all range keys has "
                        + "exceeded the size limit of 1024 bytes");
            }
            if (from != null && position.compareTo(from.s()) < 0 || to != null && position.compareTo(to.s()) > 0) {
                throw validation("The provided starting key is outside query boundaries based on provided conditions");
            }
        }
        return QueryResponse.builder().items(items).build();
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static DynamoDbException validation(String message) {
        return (DynamoDbException) DynamoDbException.builder().statusCode(400).message(message).build();
    }
}

package com.mmda.serverless.quote.api;

import static com.mmda.serverless.quote.api.Items.s;

import com.mmda.serverless.quote.domain.QuoteKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Listagem paginada por cursor no índice {@code gsi1} de uma organização, do {@code gsi1sk} maior para o menor
 * (ADR 0015 e §11 do desenho). O cursor é a posição ({@code gsi1sk}) do último item lido, e a chave da tabela sai
 * dela; o {@code gsi1pk} vem sempre da organização de quem chama, então um cursor adulterado não lê outra.
 */
final class IndexPages {

    /** WHY: limita as leituras de uma página quando o filtro descarta quase tudo; a página volta com o cursor. */
    private static final int MAX_QUERIES = 10;
    private static final int MIN_EVALUATED = 50;
    /** WHY: limite da chave de ordenação do DynamoDB; o {@code ExclusiveStartKey} maior é recusado com 400. */
    static final int MAX_POSITION_BYTES = 1024;

    private IndexPages() {
    }

    /** Filtro: condição da chave de ordenação, {@code FilterExpression} e a conferência exata no código. */
    record Filter(String sortKeyCondition, String expression, Map<String, String> names,
            Map<String, AttributeValue> values, Predicate<Map<String, AttributeValue>> accept) {

        static Filter none() {
            return new Filter(null, null, Map.of(), Map.of(), item -> true);
        }
    }

    /** Página lida; {@code nextPosition} nulo quando não há mais itens. */
    record Page(List<Map<String, AttributeValue>> items, String nextPosition) {
    }

    /**
     * @param baseKey chave da tabela do item na posição informada; a posição termina em {@code #<id>}
     */
    static Page read(DynamoDbClient dynamoDb, String table, String partition, Filter filter, String afterPosition,
            Function<String, Map<String, AttributeValue>> baseKey, int limit) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = afterPosition == null ? null : startKey(partition, afterPosition, baseKey);
        String lastEvaluated = null;
        for (int query = 0; query < MAX_QUERIES; query++) {
            QueryResponse page = dynamoDb.query(request(table, partition, filter, startKey,
                    Math.max(limit + 1, MIN_EVALUATED)));
            for (Map<String, AttributeValue> item : page.items()) {
                if (!filter.accept().test(item)) {
                    continue;
                }
                items.add(item);
                if (items.size() > limit) {
                    // INVARIANT: o cursor aponta o último item devolvido, então o item excedente volta na página seguinte
                    List<Map<String, AttributeValue>> returned = List.copyOf(items.subList(0, limit));
                    return new Page(returned, position(returned.getLast()));
                }
            }
            if (!page.hasLastEvaluatedKey() || page.lastEvaluatedKey().isEmpty()) {
                return new Page(items, null);
            }
            startKey = page.lastEvaluatedKey();
            lastEvaluated = Items.string(startKey, QuoteKeys.GSI1_SK);
        }
        return new Page(items, lastEvaluated);
    }

    static String position(Map<String, AttributeValue> item) {
        return Items.string(item, QuoteKeys.GSI1_SK);
    }

    /** Identificador no fim da posição {@code <ordem>#<id>}; nulo quando a posição está fora do formato. */
    static String idOf(String position) {
        int separator = position == null ? -1 : position.lastIndexOf('#');
        if (separator < 1 || separator == position.length() - 1) {
            return null;
        }
        return position.substring(separator + 1);
    }

    private static Map<String, AttributeValue> startKey(String partition, String position,
            Function<String, Map<String, AttributeValue>> baseKey) {
        Map<String, AttributeValue> key = new HashMap<>(baseKey.apply(position));
        key.put(QuoteKeys.GSI1_PK, s(partition));
        key.put(QuoteKeys.GSI1_SK, s(position));
        return key;
    }

    private static QueryRequest request(String table, String partition, Filter filter,
            Map<String, AttributeValue> startKey, int evaluated) {
        Map<String, String> names = new HashMap<>(filter.names());
        names.put("#gpk", QuoteKeys.GSI1_PK);
        Map<String, AttributeValue> values = new HashMap<>(filter.values());
        values.put(":gpk", s(partition));
        String keyCondition = "#gpk = :gpk";
        if (filter.sortKeyCondition() != null) {
            names.put("#gsk", QuoteKeys.GSI1_SK);
            keyCondition += " AND " + filter.sortKeyCondition();
        }
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(table)
                .indexName(QuoteKeys.GSI1)
                .keyConditionExpression(keyCondition)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .scanIndexForward(false)
                .limit(evaluated)
                .exclusiveStartKey(startKey);
        if (filter.expression() != null) {
            request.filterExpression(filter.expression());
        }
        return request.build();
    }
}

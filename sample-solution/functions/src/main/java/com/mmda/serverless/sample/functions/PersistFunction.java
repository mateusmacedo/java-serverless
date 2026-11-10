package com.mmda.serverless.sample.functions;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Component("persist")
class PersistFunction implements Function<RequestContext, RequestContext> {

    private final DynamoDbClient dynamoDb;
    private final FunctionsProperties properties;

    PersistFunction(DynamoDbClient dynamoDb, FunctionsProperties properties) {
        this.dynamoDb = dynamoDb;
        this.properties = properties;
    }

    @Override
    public RequestContext apply(RequestContext request) {
        Map<String, AttributeValue> item = Map.of(
                "requestId", AttributeValue.fromS(request.requestId()),
                "s3Key", AttributeValue.fromS(request.s3Key()),
                "signature", AttributeValue.fromS(request.signature()),
                "payloadBytes", AttributeValue.fromN(Long.toString(request.payloadBytes())),
                "attempt", AttributeValue.fromN(Integer.toString(request.attempt())),
                "persistedAt", AttributeValue.fromS(Instant.now().toString()));

        dynamoDb.putItem(builder -> builder.tableName(properties.tableName()).item(item));
        return request;
    }
}

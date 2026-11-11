package com.mmda.serverless.receipt.functions;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Emite o comprovante de um pedido concluído e grava na tabela da receipt-solution.
 * <p>
 * WHY: o EventBridge entrega cada evento pelo menos uma vez e o alvo nativo não deixa escolher o
 * nome da execução. A gravação condicional por {@code requestId} torna o passo idempotente: um
 * evento repetido devolve o comprovante já emitido.
 */
@Singleton
@Named("issue")
public class IssueReceiptHandler implements RequestHandler<CompletedRequest, Receipt> {

    private final DynamoDbClient dynamoDb;
    private final ReceiptConfig config;
    private final Clock clock;

    IssueReceiptHandler(DynamoDbClient dynamoDb, ReceiptConfig config, Clock clock) {
        this.dynamoDb = dynamoDb;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public Receipt handleRequest(CompletedRequest request, Context context) {
        Receipt receipt = new Receipt(request.requestId(), request.signature(), request.payloadBytes(),
                Instant.now(clock).toString());
        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(config.tableName())
                    .item(receipt.toItem())
                    .conditionExpression("attribute_not_exists(requestId)")
                    .build());
            return receipt;
        } catch (ConditionalCheckFailedException alreadyIssued) {
            return Receipt.fromItem(dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(config.tableName())
                    .key(Map.of("requestId", AttributeValue.fromS(request.requestId())))
                    .consistentRead(true)
                    .build()).item());
        }
    }
}

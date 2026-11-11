package com.mmda.serverless.receipt.api;

import io.floci.testcontainers.FlociContainer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Sobe um Floci com a tabela de comprovantes e uma máquina de estado de uma etapa, que basta para
 * conferir a execução iniciada pela API. Endpoint, região e credenciais seguem o padrão dos outros
 * testes de integração.
 */
public class FlociResource implements QuarkusTestResourceLifecycleManager {

    static final String TABLE = "it-api-receipts";

    private FlociContainer floci;

    @Override
    public Map<String, String> start() {
        floci = new FlociContainer(System.getProperty("floci.image", "floci/floci:2.4.0"));
        floci.start();
        System.setProperty("aws.endpointUrl", floci.getEndpoint());
        System.setProperty("aws.region", floci.getRegion());
        System.setProperty("aws.accessKeyId", floci.getAccessKey());
        System.setProperty("aws.secretAccessKey", floci.getSecretKey());

        try (DynamoDbClient dynamoDb = DynamoDbClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
             SfnClient sfn = SfnClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build()) {
            dynamoDb.createTable(builder -> builder
                    .tableName(TABLE)
                    .billingMode("PAY_PER_REQUEST")
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("requestId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("requestId").keyType(KeyType.HASH).build()));
            String stateMachineArn = sfn.createStateMachine(builder -> builder
                    .name("it-receipt-flow")
                    .roleArn("arn:aws:iam::000000000000:role/it-receipt-flow")
                    .definition("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}"))
                    .stateMachineArn();

            return Map.of(
                    "receipt.table-name", TABLE,
                    "receipt.state-machine-arn", stateMachineArn);
        }
    }

    @Override
    public void stop() {
        if (floci != null) {
            floci.stop();
        }
    }
}

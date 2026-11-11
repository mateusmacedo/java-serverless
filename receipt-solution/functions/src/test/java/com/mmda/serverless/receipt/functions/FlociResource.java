package com.mmda.serverless.receipt.functions;

import io.floci.testcontainers.FlociContainer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Sobe um Floci e cria os recursos da receipt-solution antes de o Quarkus iniciar.
 * <p>
 * WHY: endpoint, região e credenciais vão para as propriedades de sistema que o AWS SDK lê, como
 * no Spring; assim os clients do Quarkus Amazon Services seguem o mesmo caminho da Lambda no Floci.
 */
public class FlociResource implements QuarkusTestResourceLifecycleManager {

    static final String TABLE = "it-receipts";
    static final String EVENT_BUS = "it-receipt-events";
    static final String CAPTURE_QUEUE = "it-receipt-issued-capture";

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
             EventBridgeClient events = EventBridgeClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build();
             SqsClient sqs = SqsClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build()) {
            dynamoDb.createTable(builder -> builder
                    .tableName(TABLE)
                    .billingMode("PAY_PER_REQUEST")
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("requestId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("requestId").keyType(KeyType.HASH).build()));

            String queueUrl = sqs.createQueue(builder -> builder.queueName(CAPTURE_QUEUE)).queueUrl();
            String queueArn = sqs.getQueueAttributes(builder -> builder
                    .queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);

            events.createEventBus(builder -> builder.name(EVENT_BUS));
            events.putRule(builder -> builder
                    .name("it-receipt-issued")
                    .eventBusName(EVENT_BUS)
                    .eventPattern("{\"source\":[\"mmda.receipt\"],\"detail-type\":[\"ReceiptIssued\"]}"));
            events.putTargets(builder -> builder
                    .rule("it-receipt-issued")
                    .eventBusName(EVENT_BUS)
                    .targets(Target.builder().id("capture").arn(queueArn).build()));

            return Map.of(
                    "receipt.table-name", TABLE,
                    "receipt.event-bus-name", EVENT_BUS,
                    "it.capture-queue-url", queueUrl);
        }
    }

    @Override
    public void stop() {
        if (floci != null) {
            floci.stop();
        }
    }
}

package com.mmda.serverless.quote.functions;

import io.floci.testcontainers.FlociContainer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Sobe um Floci com os recursos que as functions usam: a tabela (mesmo esquema
 * do {@code serverless.yml}),
 * o segredo da cifra, um barramento com fila de captura dos eventos da quote e
 * duas máquinas de estado.
 * <p>
 * WHY: a {@code token-machine} entrega um task token real por
 * {@code sqs:sendMessage.waitForTaskToken}, sem
 * implantar Lambdas, para a {@code dispatch} e o {@code resume} trabalharem com
 * tokens de verdade; a
 * {@code flow-machine} só espera, para a relay e o reconciler iniciarem
 * execuções reais. As filas {@code results}
 * e {@code results-dlq} servem ao reprocessamento da DLQ (ADR 0007).
 */
public class FlociResource implements QuarkusTestResourceLifecycleManager {

        static final String TABLE = "it-quotes";
        static final String EVENT_BUS = "it-platform-events";
        static final String SECRET = "it/quote/token-key";

        private FlociContainer floci;

        @Override
        public Map<String, String> start() {
                floci = new FlociContainer(System.getProperty("floci.image", "floci/floci:2.4.0"));
                floci.start();
                System.setProperty("aws.endpointUrl", floci.getEndpoint());
                System.setProperty("aws.region", floci.getRegion());
                System.setProperty("aws.accessKeyId", floci.getAccessKey());
                System.setProperty("aws.secretAccessKey", floci.getSecretKey());

                try (DynamoDbClient dynamoDb = DynamoDbClient.builder().httpClientBuilder(AwsCrtHttpClient.builder())
                                .build();
                                EventBridgeClient events = EventBridgeClient.builder()
                                                .httpClientBuilder(AwsCrtHttpClient.builder()).build();
                                SqsClient sqs = SqsClient.builder().httpClientBuilder(AwsCrtHttpClient.builder())
                                                .build();
                                SfnClient sfn = SfnClient.builder().httpClientBuilder(AwsCrtHttpClient.builder())
                                                .build();
                                SecretsManagerClient secrets = SecretsManagerClient.builder()
                                                .httpClientBuilder(AwsCrtHttpClient.builder()).build()) {
                        createTable(dynamoDb);
                        secrets.createSecret(
                                        builder -> builder.name(SECRET).secretString("it-token-key-0123456789abcdef"));

                        String captureQueueUrl = queue(sqs, "it-quote-events");
                        events.createEventBus(builder -> builder.name(EVENT_BUS));
                        events.putRule(builder -> builder
                                        .name("it-quote-events")
                                        .eventBusName(EVENT_BUS)
                                        .eventPattern("{\"source\":[\"mmda.quote\"]}"));
                        events.putTargets(builder -> builder
                                        .rule("it-quote-events")
                                        .eventBusName(EVENT_BUS)
                                        .targets(Target.builder().id("capture").arn(queueArn(sqs, captureQueueUrl))
                                                        .build()));

                        String tokenQueueUrl = queue(sqs, "it-task-tokens");
                        String tokenMachineArn = sfn.createStateMachine(builder -> builder
                                        .name("it-token-machine")
                                        .roleArn("arn:aws:iam::000000000000:role/it-token-machine")
                                        .definition("""
                                                        {"StartAt":"Wait","States":{"Wait":{"Type":"Task",
                                                         "Resource":"arn:aws:states:::sqs:sendMessage.waitForTaskToken",
                                                         "Parameters":{"QueueUrl":"%s",
                                                           "MessageBody":{"taskToken.$":"$$.Task.Token","executionArn.$":"$$.Execution.Id"}},
                                                         "TimeoutSeconds":120,"End":true}}}
                                                        """
                                                        .formatted(tokenQueueUrl)))
                                        .stateMachineArn();
                        String flowMachineArn = sfn.createStateMachine(builder -> builder
                                        .name("it-quote-flow")
                                        .roleArn("arn:aws:iam::000000000000:role/it-quote-flow")
                                        .definition("{\"StartAt\":\"Hold\",\"States\":{\"Hold\":{\"Type\":\"Wait\",\"Seconds\":60,\"End\":true}}}"))
                                        .stateMachineArn();
                        // WHY: execuções que terminam em FAILED e TIMED_OUT sem consolidar, para a
                        // finalização do reconciler (T20)
                        String failMachineArn = sfn.createStateMachine(builder -> builder
                                        .name("it-fail-flow")
                                        .roleArn("arn:aws:iam::000000000000:role/it-quote-flow")
                                        .definition("{\"StartAt\":\"Boom\",\"States\":{\"Boom\":{\"Type\":\"Fail\",\"Error\":\"Boom\"}}}"))
                                        .stateMachineArn();
                        String timeoutMachineArn = sfn.createStateMachine(builder -> builder
                                        .name("it-timeout-flow")
                                        .roleArn("arn:aws:iam::000000000000:role/it-quote-flow")
                                        .definition("{\"TimeoutSeconds\":1,\"StartAt\":\"Hold\","
                                                        + "\"States\":{\"Hold\":{\"Type\":\"Wait\",\"Seconds\":30,\"End\":true}}}"))
                                        .stateMachineArn();
                        // WHY: visibilidade de 1 s e um recebimento levam a mensagem à DLQ em segundos,
                        // pela RedrivePolicy (T31)
                        String deadLetterQueueUrl = queue(sqs, "it-quote-results-dlq");
                        String resultsQueueUrl = sqs.createQueue(builder -> builder.queueName("it-quote-results")
                                        .attributes(Map.of(
                                                        QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                                                        QueueAttributeName.REDRIVE_POLICY,
                                                        "{\"deadLetterTargetArn\":\""
                                                                        + queueArn(sqs, deadLetterQueueUrl)
                                                                        + "\",\"maxReceiveCount\":\"1\"}")))
                                        .queueUrl();

                        String issuesDeadLetterQueueUrl = queue(sqs, "it-quote-issues-dlq");
                        String issuesQueueUrl = queue(sqs, "it-quote-issues");
                        return Map.ofEntries(
                                        Map.entry("quote.table-name", TABLE),
                                        Map.entry("reconciler.issues-queue-url", issuesQueueUrl),
                                        Map.entry("reconciler.issues-dead-letter-queue-url", issuesDeadLetterQueueUrl),
                                        Map.entry("quote.event-bus-name", EVENT_BUS),
                                        Map.entry("quote.state-machine-arn", flowMachineArn),
                                        Map.entry("quote.token-key-secret-id", SECRET),
                                        Map.entry("reconciler.results-queue-url", resultsQueueUrl),
                                        Map.entry("reconciler.results-dead-letter-queue-url", deadLetterQueueUrl),
                                        Map.entry("it.capture-queue-url", captureQueueUrl),
                                        Map.entry("it.token-queue-url", tokenQueueUrl),
                                        Map.entry("it.token-machine-arn", tokenMachineArn),
                                        Map.entry("it.fail-machine-arn", failMachineArn),
                                        Map.entry("it.timeout-machine-arn", timeoutMachineArn));
                }
        }

        private static String queue(SqsClient sqs, String name) {
                return sqs.createQueue(builder -> builder.queueName(name)).queueUrl();
        }

        private static String queueArn(SqsClient sqs, String queueUrl) {
                return sqs.getQueueAttributes(
                                builder -> builder.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                                .attributes().get(QueueAttributeName.QUEUE_ARN);
        }

        private static void createTable(DynamoDbClient dynamoDb) {
                dynamoDb.createTable(builder -> builder
                                .tableName(TABLE)
                                .billingMode("PAY_PER_REQUEST")
                                .attributeDefinitions(attribute("pk"), attribute("sk"), attribute("gsi1pk"),
                                                attribute("gsi1sk"),
                                                attribute("gsi2pk"), attribute("gsi2sk"))
                                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                                .globalSecondaryIndexes(index("gsi1"), index("gsi2"))
                                .streamSpecification(stream -> stream.streamEnabled(true)
                                                .streamViewType(StreamViewType.NEW_IMAGE)));
        }

        private static GlobalSecondaryIndex index(String name) {
                return GlobalSecondaryIndex.builder()
                                .indexName(name)
                                .keySchema(key(name + "pk", KeyType.HASH), key(name + "sk", KeyType.RANGE))
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build();
        }

        private static AttributeDefinition attribute(String name) {
                return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
        }

        private static KeySchemaElement key(String name, KeyType type) {
                return KeySchemaElement.builder().attributeName(name).keyType(type).build();
        }

        @Override
        public void stop() {
                if (floci != null) {
                        floci.stop();
                }
        }
}

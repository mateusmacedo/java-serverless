package com.mmda.serverless.provider.functions;

import io.floci.testcontainers.FlociContainer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Um único Floci e os recursos da provider, com o mesmo esquema do
 * serverless.yml, para todos os testes de
 * integração do módulo. Os fornecedores são o {@link ProviderStub}, em
 * processo.
 * <p>
 * WHY: endpoint, região e credenciais vão para as propriedades de sistema que o
 * AWS SDK lê nas cadeias
 * padrão, então os testes passam pela autoconfiguração real do spring-aws.
 */
@SpringBootTest(classes = FunctionsApplication.class)
abstract class FlociIntegrationTest {

    static final FlociContainer FLOCI = new FlociContainer(System.getProperty("floci.image", "floci/floci:2.4.0"));

    static final String TABLE = "it-provider-queries";
    static final String BUCKET = "it-provider-payloads";
    static final String EVENT_BUS = "it-platform-events";
    static final String QUERY_KEY_SECRET = "it/provider/query-key-secret";
    static final String ENRICHMENT_PROVIDER = "enrichment-sim";
    static final String CAPPED_PROVIDER = "capped";
    /**
     * Provedores com quota pequena, só do T21: nenhum outro teste gasta a janela
     * deles.
     */
    static final String T21_INSURER = "t21-insurer";
    static final String T21_ENRICHMENT = "t21-enrichment";
    static final int T21_QUOTA_PER_MINUTE = 3;
    static final ProviderStub PROVIDERS;
    static final DynamoDbClient DYNAMO_DB;
    static final SqsClient SQS;
    static final String ENRICHMENT_QUEUE_URL;
    static final String INSURER_QUEUE_URL;
    static final String CAPTURE_QUEUE_URL;

    static {
        FLOCI.start();
        System.setProperty("aws.endpointUrl", FLOCI.getEndpoint());
        System.setProperty("aws.region", FLOCI.getRegion());
        System.setProperty("aws.accessKeyId", FLOCI.getAccessKey());
        System.setProperty("aws.secretAccessKey", FLOCI.getSecretKey());

        DYNAMO_DB = DynamoDbClient.create();
        SQS = SqsClient.create();
        createQueriesTable();
        try (S3Client s3 = S3Client.builder().forcePathStyle(true).build();
                SecretsManagerClient secrets = SecretsManagerClient.create()) {
            s3.createBucket(builder -> builder.bucket(BUCKET));
            secrets.createSecret(builder -> builder.name(QUERY_KEY_SECRET).secretString("it-query-key-secret"));
        }
        ENRICHMENT_QUEUE_URL = SQS.createQueue(builder -> builder.queueName("it-enrichment-requests")).queueUrl();
        INSURER_QUEUE_URL = SQS.createQueue(builder -> builder.queueName("it-insurer-requests")).queueUrl();
        CAPTURE_QUEUE_URL = SQS.createQueue(builder -> builder.queueName("it-provider-events")).queueUrl();
        try (EventBridgeClient events = EventBridgeClient.create()) {
            events.createEventBus(builder -> builder.name(EVENT_BUS));
            events.putRule(builder -> builder
                    .name("it-provider-events")
                    .eventBusName(EVENT_BUS)
                    .eventPattern("{\"source\":[\"mmda.provider\"]}"));
            events.putTargets(builder -> builder
                    .rule("it-provider-events")
                    .eventBusName(EVENT_BUS)
                    .targets(Target.builder().id("capture").arn(queueArn(CAPTURE_QUEUE_URL)).build()));
        }
        PROVIDERS = ProviderStub.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("TABLE_NAME", () -> TABLE);
        registry.add("PAYLOADS_BUCKET", () -> BUCKET);
        registry.add("EVENT_BUS_NAME", () -> EVENT_BUS);
        registry.add("QUERY_KEY_SECRET_ID", () -> QUERY_KEY_SECRET);
        registry.add("PROVIDERS_BASE_URL", PROVIDERS::baseUrl);
        registry.add("ENRICHMENT_REQUESTS_QUEUE_URL", () -> ENRICHMENT_QUEUE_URL);
        registry.add("INSURER_REQUESTS_QUEUE_URL", () -> INSURER_QUEUE_URL);
        registry.add("S3_PATH_STYLE_ACCESS", () -> "true");
        registry.add("PROVIDER_HTTP_TIMEOUT_SECONDS", () -> "1");
        registry.add("RETRY_BACKOFF_MAX_SECONDS", () -> "0");
        registry.add("ENRICHMENT_SIM_QUOTA_PER_MINUTE", () -> "1000");
        registry.add("ALPHA_QUOTA_PER_MINUTE", () -> "1000");
        registry.add("BETA_QUOTA_PER_MINUTE", () -> "1000");
        registry.add("provider.quota-per-minute[" + CAPPED_PROVIDER + "]", () -> "1");
        registry.add("provider.quota-per-minute[" + T21_INSURER + "]", () -> String.valueOf(T21_QUOTA_PER_MINUTE));
        registry.add("provider.quota-per-minute[" + T21_ENRICHMENT + "]", () -> String.valueOf(T21_QUOTA_PER_MINUTE));
        registry.add("provider.supported-insurers", () -> "alpha,beta," + CAPPED_PROVIDER + "," + T21_INSURER);
    }

    static Map<String, AttributeValue> item(String pk, String sk) {
        return DYNAMO_DB.getItem(builder -> builder
                .tableName(TABLE)
                .consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk))))
                .item();
    }

    static Optional<String> attribute(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(value.s() != null ? value.s() : value.n());
    }

    /**
     * Itens da partição {@code QUERY#<queryId>} cujo {@code sk} começa com o
     * prefixo, em ordem.
     */
    static List<Map<String, AttributeValue>> queryItems(String queryId, String skPrefix) {
        return DYNAMO_DB.query(builder -> builder
                .tableName(TABLE)
                .consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS("QUERY#" + queryId),
                        ":prefix", AttributeValue.fromS(skPrefix))))
                .items();
    }

    static String queueArn(String queueUrl) {
        return SQS.getQueueAttributes(builder -> builder
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static void createQueriesTable() {
        Map<String, ScalarAttributeType> attributes = new HashMap<>();
        attributes.put("pk", ScalarAttributeType.S);
        attributes.put("sk", ScalarAttributeType.S);
        attributes.put("gsi2pk", ScalarAttributeType.S);
        attributes.put("gsi2sk", ScalarAttributeType.S);
        DYNAMO_DB.createTable(builder -> builder
                .tableName(TABLE)
                .billingMode("PAY_PER_REQUEST")
                .attributeDefinitions(attributes.entrySet().stream()
                        .map(entry -> AttributeDefinition.builder()
                                .attributeName(entry.getKey()).attributeType(entry.getValue()).build())
                        .toList())
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("gsi2")
                        .keySchema(key("gsi2pk", KeyType.HASH), key("gsi2sk", KeyType.RANGE))
                        .projection(projection -> projection.projectionType(ProjectionType.ALL))
                        .build())
                .streamSpecification(stream -> stream.streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE)));
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}

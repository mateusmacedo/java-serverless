package com.mmda.serverless.provider.api;

import io.floci.testcontainers.FlociContainer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Um único Floci e os recursos que a API de callbacks usa, com o mesmo esquema do serverless.yml: a tabela de
 * consultas, o bucket de payloads e o {@code callback-secret}.
 * <p>
 * WHY: endpoint, região, credenciais e as variáveis da Lambda vão para as propriedades de sistema, que o AWS SDK
 * e o Spring leem; assim os testes e o handler da Lambda passam pela autoconfiguração real do spring-aws.
 */
abstract class FlociIntegrationTest {

    static final FlociContainer FLOCI = new FlociContainer(System.getProperty("floci.image", "floci/floci:2.4.0"));

    static final String TABLE = "it-provider-queries";
    static final String BUCKET = "it-provider-payloads";
    static final String CALLBACK_SECRET = "it/provider/callback-secret";
    static final String CALLBACK_SECRET_VALUE = "it-callback-secret-value";
    static final DynamoDbClient DYNAMO_DB;

    static {
        FLOCI.start();
        System.setProperty("aws.endpointUrl", FLOCI.getEndpoint());
        System.setProperty("aws.region", FLOCI.getRegion());
        System.setProperty("aws.accessKeyId", FLOCI.getAccessKey());
        System.setProperty("aws.secretAccessKey", FLOCI.getSecretKey());
        System.setProperty("TABLE_NAME", TABLE);
        System.setProperty("PAYLOADS_BUCKET", BUCKET);
        System.setProperty("CALLBACK_SECRET_ID", CALLBACK_SECRET);
        System.setProperty("S3_PATH_STYLE_ACCESS", "true");
        DYNAMO_DB = DynamoDbClient.create();
        DYNAMO_DB.createTable(builder -> builder
                .tableName(TABLE)
                .billingMode("PAY_PER_REQUEST")
                .attributeDefinitions(attribute("pk"), attribute("sk"), attribute("gsi2pk"), attribute("gsi2sk"))
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("gsi2")
                        .keySchema(key("gsi2pk", KeyType.HASH), key("gsi2sk", KeyType.RANGE))
                        .projection(projection -> projection.projectionType(ProjectionType.ALL))
                        .build()));
        try (S3Client s3 = S3Client.builder().forcePathStyle(true).build();
             SecretsManagerClient secrets = SecretsManagerClient.create()) {
            s3.createBucket(builder -> builder.bucket(BUCKET));
            secrets.createSecret(builder -> builder.name(CALLBACK_SECRET).secretString(CALLBACK_SECRET_VALUE));
        }
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

    /** Itens da partição {@code QUERY#<queryId>} cujo {@code sk} começa com o prefixo. */
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

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}

package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.testcontainers.FlociContainer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;

/**
 * Sobe um Floci com a tabela da quote-solution (mesmo esquema do {@code serverless.yml}) e um user pool
 * Cognito com app client, grupos e usuários. Os tokens são access tokens reais do pool.
 * <p>
 * WHY: o issuer é lido do {@code openid-configuration} do container e o JWKS é apontado na porta
 * mapeada, porque o Floci de teste sobe sem {@code FLOCI_HOSTNAME} (§11 do desenho).
 */
public class FlociResource implements QuarkusTestResourceLifecycleManager {

    static final String TABLE = "it-api-quotes";
    static final String PASSWORD = "Lab-Passw0rd!";

    /** Usuário de teste e os grupos do Cognito que ele recebe. */
    record TestUser(String name, List<String> groups) {
    }

    static final TestUser WRITER = new TestUser("ana-writer",
            List.of("org_org-acme", "perm_quotes_read", "perm_quotes_write", "perm_offers_select"));
    static final TestUser READER = new TestUser("rui-reader", List.of("org_org-acme", "perm_quotes_read"));
    static final TestUser GLOBEX = new TestUser("carla-globex",
            List.of("org_org-globex", "perm_quotes_read", "perm_quotes_write"));

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
             CognitoIdentityProviderClient cognito = CognitoIdentityProviderClient.builder()
                     .httpClientBuilder(AwsCrtHttpClient.builder()).build()) {
            createTable(dynamoDb);

            String poolId = cognito.createUserPool(builder -> builder.poolName("it-quote-users")).userPool().id();
            String clientId = cognito.createUserPoolClient(builder -> builder
                    .userPoolId(poolId)
                    .clientName("it-quote-web")
                    .generateSecret(false)
                    .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
                            ExplicitAuthFlowsType.ALLOW_REFRESH_TOKEN_AUTH))
                    .userPoolClient().clientId();
            for (String group : List.of("org_org-acme", "org_org-globex", "perm_quotes_read", "perm_quotes_write",
                    "perm_offers_select")) {
                cognito.createGroup(builder -> builder.userPoolId(poolId).groupName(group));
            }

            Map<String, String> properties = new HashMap<>();
            for (TestUser user : List.of(WRITER, READER, GLOBEX)) {
                createUser(cognito, poolId, user);
                var tokens = cognito.initiateAuth(builder -> builder
                        .clientId(clientId)
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .authParameters(Map.of("USERNAME", user.name(), "PASSWORD", PASSWORD)))
                        .authenticationResult();
                properties.put("it.access-token." + user.name(), tokens.accessToken());
                properties.put("it.id-token." + user.name(), tokens.idToken());
            }

            String poolUrl = floci.getEndpoint() + "/" + poolId;
            String issuer = new ObjectMapper()
                    .readTree(get(poolUrl + "/.well-known/openid-configuration"))
                    .get("issuer").asText();
            properties.put("quote.table-name", TABLE);
            properties.put("quote.auth.client-id", clientId);
            properties.put("mp.jwt.verify.issuer", issuer);
            properties.put("mp.jwt.verify.publickey.location", poolUrl + "/.well-known/jwks.json");
            return properties;
        } catch (Exception exception) {
            throw new IllegalStateException("could not prepare Floci for the API tests", exception);
        }
    }

    private static void createUser(CognitoIdentityProviderClient cognito, String poolId, TestUser user) {
        cognito.adminCreateUser(builder -> builder
                .userPoolId(poolId)
                .username(user.name())
                .messageAction(MessageActionType.SUPPRESS)
                .userAttributes(AttributeType.builder().name("email").value(user.name() + "@example.test").build()));
        cognito.adminSetUserPassword(builder -> builder
                .userPoolId(poolId).username(user.name()).password(PASSWORD).permanent(true));
        for (String group : user.groups()) {
            cognito.adminAddUserToGroup(builder -> builder.userPoolId(poolId).username(user.name()).groupName(group));
        }
    }

    static void createTable(DynamoDbClient dynamoDb) {
        dynamoDb.createTable(builder -> builder
                .tableName(TABLE)
                .billingMode("PAY_PER_REQUEST")
                .attributeDefinitions(
                        attribute("pk"), attribute("sk"), attribute("gsi1pk"), attribute("gsi1sk"),
                        attribute("gsi2pk"), attribute("gsi2sk"))
                .keySchema(key("pk", KeyType.HASH), key("sk", KeyType.RANGE))
                .globalSecondaryIndexes(index("gsi1"), index("gsi2"))
                .streamSpecification(stream -> stream.streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE)));
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

    private static String get(String url) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(url + " answered " + response.statusCode());
            }
            return response.body();
        }
    }

    @Override
    public void stop() {
        if (floci != null) {
            floci.stop();
        }
    }
}

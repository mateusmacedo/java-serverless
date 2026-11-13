package com.mmda.serverless.quote.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.RestApi;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolClientDescription;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolDescriptionType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;

/**
 * Ponta a ponta do M1 (§14 do desenho) no Floci, com as stacks platform, provider-solution e quote-solution
 * implantadas. Cada espera usa o orçamento do stage (invariante do §9) lido do {@code serverless.yml}, sem supor
 * funções aquecidas, porque o Floci remove containers ociosos.
 */
class QuoteFlowE2E {

    private static final String STAGE = System.getProperty("e2e.stage", "local");
    private static final String QUOTE_PREFIX = "quote-solution-" + STAGE;
    private static final String PROVIDER_PREFIX = "provider-solution-" + STAGE;
    private static final String QUOTES_TABLE = QUOTE_PREFIX + "-quotes";
    private static final String QUERIES_TABLE = PROVIDER_PREFIX + "-queries";
    private static final String PAYLOADS_BUCKET = PROVIDER_PREFIX + "-payloads";

    private static final String USERNAME = "e2e-acme-writer";
    private static final String PASSWORD = "E2e-Quote-Passw0rd!";
    private static final List<String> GROUPS = List.of("org_org-acme", "perm_quotes_read", "perm_quotes_write");

    private static final String APPLICANT = "customer-acme-frota";
    private static final String DRIVER = "driver-acme-bruno";
    private static final String DRIVER_WITHOUT_LICENSE_DATE = "driver-no-license";
    private static final String VAN = "vehicle-van-2022";
    private static final String SALVAGE = "vehicle-salvage";
    private static final String DECIMAL = "^\\d+(\\.\\d+)?$";
    private static final String ORGANIZATION = "org-acme";
    /** Dois disparos de {@code rate(1 minute)} e a publicação; o Floci dispara a cada 60 a 70 s. */
    private static final Duration RECONCILER_BUDGET = Duration.ofMinutes(3);
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Formato do {@code Timestamps} da provider, comparado como texto no {@code gsi2sk}. */
    private static final DateTimeFormatter PROVIDER_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.create();
    private static final SfnClient SFN = SfnClient.create();
    private static final S3Client S3 = S3Client.builder().forcePathStyle(true).build();

    private static StageParameters stage;
    private static String apiBase;
    private static String accessToken;
    private static String stateMachineArn;
    private static String desiredStartDate;

    @BeforeAll
    static void locateTheStacksAndSignIn() {
        stage = StageParameters.load(Path.of("../serverless.yml"), STAGE);
        desiredStartDate = LocalDate.now(ZoneOffset.UTC).plusDays(30).toString();
        try (ApiGatewayClient apiGateway = ApiGatewayClient.create()) {
            apiBase = baseUrlOf(apiGateway, STAGE + "-quote-solution");
        }
        stateMachineArn = SFN.listStateMachinesPaginator(builder -> builder.maxResults(100)).stateMachines().stream()
                .filter(machine -> machine.name().equals(QUOTE_PREFIX + "-quote-flow"))
                .map(StateMachineListItem::stateMachineArn)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("state machine not found; deploy quote-solution first"));
        accessToken = signIn();
    }

    @AfterAll
    static void closeClients() {
        DYNAMO_DB.close();
        SFN.close();
        S3.close();
    }

    @Test
    void t01_commercialVanQuotesOnlyAtAlphaAndCompletesWithOneOfferAndEvidence() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER)));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(1));

        assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(version.path("outcomeReason").isNull()).isTrue();
        assertThat(version.path("missingFields")).isEmpty();
        JsonNode results = version.path("insurerResults");
        assertThat(results).hasSize(1);
        JsonNode alpha = results.get(0);
        assertThat(alpha.path("insurerId").asText()).isEqualTo("alpha");
        assertThat(alpha.path("status").asText()).isEqualTo("QUOTED");

        JsonNode offers = getOk(accepted.statusUrl() + "/offers").path("items");
        assertThat(offers).hasSize(1);
        JsonNode offer = offers.get(0);
        assertThat(offer.path("insurerId").asText()).isEqualTo("alpha");
        assertThat(offer.path("insurerName").asText()).isEqualTo(alpha.path("insurerName").asText());
        assertThat(offer.path("premiumTotal").asText()).matches(DECIMAL);
        assertThat(offer.path("currency").asText()).isEqualTo("BRL");
        assertThat(offer.path("coverages")).isNotEmpty();
        assertThat(offer.path("selectable").asBoolean()).isTrue();
        assertThat(offer.path("unavailabilityReason").isNull()).isTrue();

        assertEnrichmentEvidence(accepted.quoteId());
        assertInsurerEvidence(accepted.quoteId(), offer.path("id").asText());
        assertThat(awaitSingleExecution(accepted.quoteId()).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void t02_resubmissionReturnsSameQuote() throws Exception {
        String key = newKey();
        Map<String, Object> request = request(VAN, "COMMERCIAL", DRIVER);
        Instant started = Instant.now();

        Accepted first = create(key, JSON.writeValueAsString(request));
        Accepted whileRunning = create(key, reorderedAndIndented(request));
        JsonNode version = awaitTerminal(first, stage.withInsurers(1));
        Accepted afterTerminal = create(key, JSON.writeValueAsString(request));

        assertThat(whileRunning.identity()).isEqualTo(first.identity());
        assertThat(afterTerminal.identity()).isEqualTo(first.identity());
        assertThat(quotesOfTheOrganizationCreatedBetween(started, Instant.now()))
                .as("the resubmissions created no other quote").containsExactly(first.quoteId());
        assertThat(version.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(executionsOf(first.quoteId())).as("no execution of another version").hasSize(1);
    }

    @Test
    void t03_sameKeyDifferentBodyReturnsConflict() throws Exception {
        String key = newKey();
        Instant started = Instant.now();
        Accepted original = create(key, JSON.writeValueAsString(request(SALVAGE, "PERSONAL", DRIVER)));

        HttpResponse<String> conflict = post(key, JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER)));

        assertThat(conflict.statusCode()).isEqualTo(409);
        JsonNode error = JSON.readTree(conflict.body());
        assertThat(error.path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(error.path("correlationId").asText()).isNotBlank();
        JsonNode version = awaitTerminal(original, stage.withoutExternalWaits());
        assertThat(version.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(version.at("/snapshot/risk/vehicleRef").asText()).isEqualTo(SALVAGE);
        assertThat(version.at("/snapshot/risk/usage").asText()).isEqualTo("PERSONAL");
        assertThat(quotesOfTheOrganizationCreatedBetween(started, Instant.now()))
                .as("the conflicting request created no quote").containsExactly(original.quoteId());
        assertThat(executionsOf(original.quoteId())).as("no execution of another version").hasSize(1);
    }

    /**
     * T19 com o agendamento real: um outbox pendente que o stream nunca entregou (gravado sem o {@code type}
     * {@code OUTBOX} no {@code INSERT}, que é o filtro da relay) só é publicado pelo {@code reconciler}
     * disparado pelo {@code rate(1 minute)}, nas duas soluções.
     */
    @Test
    void t19_scheduledReconcilersRepublishOutboxTheStreamNeverDelivered() {
        Instant stale = Instant.now().minusSeconds(600);
        String quoteEvent = givenOutboxOutsideTheStream(QUOTES_TABLE, stale.toString());
        String providerEvent = givenOutboxOutsideTheStream(QUERIES_TABLE, PROVIDER_TIMESTAMP.format(stale));

        await().atMost(RECONCILER_BUDGET).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(outbox(QUOTES_TABLE, quoteEvent)).as("quote outbox").containsKey("publishedAt")
                    .doesNotContainKey("gsi2pk");
            assertThat(outbox(QUERIES_TABLE, providerEvent)).as("provider outbox").containsKey("publishedAt")
                    .doesNotContainKey("gsi2pk");
        });
    }

    @Test
    void t23_salvageVehicleIsIneligibleWithoutExternalQuery() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(SALVAGE, "PERSONAL", DRIVER)));

        JsonNode version = awaitTerminal(accepted, stage.withoutExternalWaits());

        assertThat(version.path("outcome").asText()).isEqualTo("INELIGIBLE");
        assertThat(version.path("outcomeReason").asText()).isEqualTo("SALVAGE_VEHICLE");
        assertThat(version.path("insurerResults")).isEmpty();
        assertThat(getOk(accepted.statusUrl() + "/offers").path("items")).isEmpty();
        assertNoExternalQuery(accepted.quoteId());
    }

    @Test
    void t24_driverWithoutLicenseDateNeedsInformationBeforeAnyQuery() throws Exception {
        Accepted accepted = create(newKey(),
                JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER_WITHOUT_LICENSE_DATE)));

        JsonNode version = awaitTerminal(accepted, stage.withoutExternalWaits());

        assertThat(version.path("outcome").asText()).isEqualTo("NEEDS_INFORMATION");
        assertThat(version.path("outcomeReason").asText()).isEqualTo("MISSING_LICENSE_DATE");
        JsonNode missingFields = version.path("missingFields");
        assertThat(missingFields).hasSize(1);
        assertThat(missingFields.get(0).path("path").asText()).isEqualTo("risk.principalDriverRef");
        assertThat(missingFields.get(0).path("reasonCode").asText()).isEqualTo("MISSING_LICENSE_DATE");
        assertThat(missingFields.get(0).path("label").asText()).isNotBlank();
        assertThat(version.path("insurerResults")).isEmpty();
        assertNoExternalQuery(accepted.quoteId());
    }

    /** Evidência usada do enriquecimento na quote, com a consulta e a evidência correspondentes na provider. */
    private static void assertEnrichmentEvidence(String quoteId) {
        Map<String, AttributeValue> used = single(quoteItems(quoteId, "V#0001#EVIDENCE#"));
        assertThat(string(used, "resultType")).isEqualTo("FOUND");
        assertThat(string(used, "reuseDecision")).isEqualTo("MISS");
        String queryId = string(used, "queryId");
        assertThat(string(providerQuery(queryId), "state")).isEqualTo("COMPLETED");
        Map<String, AttributeValue> evidence = single(providerItems(queryId, "EVIDENCE#"));
        assertThat(string(evidence, "sk")).isEqualTo("EVIDENCE#" + string(used, "evidenceId"));
        assertThat(string(evidence, "resultType")).isEqualTo("FOUND");
    }

    /** A oferta aponta a consulta da provider, cuja evidência guarda a resposta bruta no bucket de payloads. */
    private static void assertInsurerEvidence(String quoteId, String offerId) {
        Map<String, AttributeValue> offer = single(quoteItems(quoteId, "V#0001#OFFER#" + offerId));
        String queryId = string(offer, "queryId");
        assertThat(string(providerQuery(queryId), "state")).isEqualTo("COMPLETED");
        Map<String, AttributeValue> evidence = single(providerItems(queryId, "EVIDENCE#"));
        String rawResponseRef = string(evidence, "rawResponseRef");
        assertThat(rawResponseRef).isEqualTo(string(offer, "rawResponseRef"));
        assertThat(S3.headObject(builder -> builder.bucket(PAYLOADS_BUCKET).key(rawResponseRef)).contentLength())
                .isPositive();
        Map<String, AttributeValue> result = single(quoteItems(quoteId, "V#0001#RESULT#alpha"));
        assertThat(string(result, "queryRef")).isEqualTo(queryId);
    }

    /** T23 e T24: nenhum pedido saiu da quote e a provider não abriu consulta para a cotação. */
    private static void assertNoExternalQuery(String quoteId) {
        assertThat(quoteItems(quoteId, "V#0001#")).isEmpty();
        assertThat(DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUOTES_TABLE)
                        .filterExpression("begins_with(pk, :waiter) AND quoteId = :quoteId")
                        .expressionAttributeValues(Map.of(":waiter", AttributeValue.fromS("WAITER#"),
                                ":quoteId", AttributeValue.fromS(quoteId))))
                .items().stream().count()).as("waiters").isZero();
        assertThat(DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUERIES_TABLE)
                        .filterExpression("quoteId = :quoteId")
                        .expressionAttributeValues(Map.of(":quoteId", AttributeValue.fromS(quoteId))))
                .items().stream().count()).as("provider queries").isZero();
    }

    /**
     * Item {@code OUTBOX_PENDING} antigo que a relay nunca vê: o {@code INSERT} sai com outro {@code type} e só o
     * {@code MODIFY} seguinte o marca {@code OUTBOX}. O {@code detail-type} não tem regra no barramento.
     */
    private static String givenOutboxOutsideTheStream(String table, String createdAt) {
        String eventId = UUID.randomUUID().toString();
        Map<String, AttributeValue> key = Map.of("pk", AttributeValue.fromS("OUTBOX#" + eventId),
                "sk", AttributeValue.fromS("META"));
        Map<String, AttributeValue> item = new LinkedHashMap<>(key);
        item.put("type", AttributeValue.fromS("E2E_PROBE"));
        item.put("eventId", AttributeValue.fromS(eventId));
        item.put("detailType", AttributeValue.fromS("ReconcilerProbe"));
        item.put("detail", AttributeValue.fromS("{\"eventId\":\"" + eventId + "\",\"schemaVersion\":\"1\"}"));
        item.put("createdAt", AttributeValue.fromS(createdAt));
        item.put("attempts", AttributeValue.fromN("0"));
        item.put("gsi2pk", AttributeValue.fromS("OUTBOX_PENDING"));
        item.put("gsi2sk", AttributeValue.fromS(createdAt));
        DYNAMO_DB.putItem(builder -> builder.tableName(table).item(item));
        DYNAMO_DB.updateItem(builder -> builder.tableName(table).key(key)
                .updateExpression("SET #type = :outbox")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":outbox", AttributeValue.fromS("OUTBOX"))));
        return eventId;
    }

    private static Map<String, AttributeValue> outbox(String table, String eventId) {
        return single(queryByPrefix(table, "OUTBOX#" + eventId, "META"));
    }

    /** {@code quoteId} das cotações da organização aceitas na janela, pelo {@code gsi1} ({@code createdAt#quoteId}). */
    private static List<String> quotesOfTheOrganizationCreatedBetween(Instant from, Instant to) {
        return DYNAMO_DB.queryPaginator(builder -> builder.tableName(QUOTES_TABLE).indexName("gsi1")
                        .keyConditionExpression("gsi1pk = :organization AND gsi1sk BETWEEN :from AND :to")
                        .expressionAttributeValues(Map.of(
                                ":organization", AttributeValue.fromS("ORG#" + ORGANIZATION),
                                ":from", AttributeValue.fromS(SECONDS.format(from.minusSeconds(2))),
                                ":to", AttributeValue.fromS(SECONDS.format(to.plusSeconds(3))))))
                .items().stream()
                .map(item -> item.get("gsi1sk").s())
                .map(sortKey -> sortKey.substring(sortKey.indexOf('#') + 1))
                .toList();
    }

    private static JsonNode awaitTerminal(Accepted accepted, Duration budget) {
        return await().atMost(budget).pollInterval(stage.pollInterval())
                .until(() -> getOk(accepted.statusUrl()), version -> "TERMINAL".equals(version.path("phase").asText()));
    }

    private static ExecutionListItem awaitSingleExecution(String quoteId) {
        return await().atMost(Duration.ofSeconds(stage.stepsMarginSeconds())).pollInterval(Duration.ofSeconds(1))
                .until(() -> executionsOf(quoteId),
                        executions -> executions.size() == 1 && executions.getFirst().status() != ExecutionStatus.RUNNING)
                .getFirst();
    }

    private static List<ExecutionListItem> executionsOf(String quoteId) {
        String prefix = "q-" + quoteId + "-";
        return SFN.listExecutionsPaginator(builder -> builder.stateMachineArn(stateMachineArn).maxResults(100))
                .executions().stream()
                .filter(execution -> execution.name().startsWith(prefix))
                .toList();
    }

    private static List<Map<String, AttributeValue>> quoteItems(String quoteId, String skPrefix) {
        return queryByPrefix(QUOTES_TABLE, "QUOTE#" + quoteId, skPrefix);
    }

    private static List<Map<String, AttributeValue>> providerItems(String queryId, String skPrefix) {
        return queryByPrefix(QUERIES_TABLE, "QUERY#" + queryId, skPrefix);
    }

    private static Map<String, AttributeValue> providerQuery(String queryId) {
        return single(providerItems(queryId, "META"));
    }

    private static List<Map<String, AttributeValue>> queryByPrefix(String table, String pk, String skPrefix) {
        return DYNAMO_DB.query(builder -> builder.tableName(table).consistentRead(true)
                .keyConditionExpression("pk = :pk AND begins_with(sk, :prefix)")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(pk),
                        ":prefix", AttributeValue.fromS(skPrefix))))
                .items();
    }

    private static Map<String, AttributeValue> single(List<Map<String, AttributeValue>> items) {
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private static String string(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        assertThat(value).as(attribute).isNotNull();
        return value.s();
    }

    private static Map<String, Object> request(String vehicleRef, String usage, String driverRef) {
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("vehicleRef", vehicleRef);
        risk.put("usage", usage);
        risk.put("principalDriverRef", driverRef);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("product", "AUTO");
        request.put("applicantRef", APPLICANT);
        request.put("risk", risk);
        request.put("coverageProfile", "COMPREHENSIVE");
        request.put("desiredStartDate", desiredStartDate);
        return request;
    }

    /** O mesmo conteúdo em outra ordem de campos e com espaços: o hash é do JSON canônico (RFC 8785). */
    @SuppressWarnings("unchecked")
    private static String reorderedAndIndented(Map<String, Object> request) throws Exception {
        Map<String, Object> reordered = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(request.keySet());
        Collections.reverse(keys);
        for (String key : keys) {
            Object value = request.get(key);
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> reversed = new LinkedHashMap<>();
                List<String> nestedKeys = new ArrayList<>((Set<String>) nested.keySet());
                Collections.reverse(nestedKeys);
                nestedKeys.forEach(nestedKey -> reversed.put(nestedKey, nested.get(nestedKey)));
                value = reversed;
            }
            reordered.put(key, value);
        }
        return JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(reordered);
    }

    private static Accepted create(String idempotencyKey, String body) throws Exception {
        HttpResponse<String> response = post(idempotencyKey, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        JsonNode accepted = JSON.readTree(response.body());
        assertThat(response.headers().firstValue("Location")).contains(accepted.path("statusUrl").asText());
        return new Accepted(accepted.path("quoteId").asText(), accepted.path("version").asInt(),
                accepted.path("statusUrl").asText());
    }

    private static HttpResponse<String> post(String idempotencyKey, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(apiBase + "/v1/quotes"))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", idempotencyKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofMinutes(1))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode getOk(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(apiBase + path))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .timeout(Duration.ofMinutes(1))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static String newKey() {
        return "e2e-" + UUID.randomUUID();
    }

    private static String baseUrlOf(ApiGatewayClient apiGateway, String apiName) {
        String endpoint = System.getProperty("aws.endpointUrl", "http://localhost:4566");
        String apiId = apiGateway.getRestApis(builder -> builder.limit(500)).items().stream()
                .filter(api -> api.name().equals(apiName))
                .map(RestApi::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("API " + apiName + " not found; deploy quote-solution first"));
        return endpoint + "/restapis/" + apiId + "/" + STAGE + "/_user_request_";
    }

    /**
     * Garante o usuário de teste nos grupos da organização e das permissões (APIs administrativas do Cognito) e
     * devolve um access token obtido com {@code USER_PASSWORD_AUTH}, que só a Lambda da API valida.
     */
    private static String signIn() {
        try (CognitoIdentityProviderClient cognito = CognitoIdentityProviderClient.create()) {
            String poolId = cognito.listUserPoolsPaginator(builder -> builder.maxResults(60)).userPools().stream()
                    .filter(pool -> pool.name().equals(QUOTE_PREFIX + "-users"))
                    .map(UserPoolDescriptionType::id)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("user pool not found; deploy quote-solution first"));
            String clientId = cognito.listUserPoolClients(builder -> builder.userPoolId(poolId).maxResults(60))
                    .userPoolClients().stream()
                    .filter(client -> client.clientName().equals(QUOTE_PREFIX + "-web"))
                    .map(UserPoolClientDescription::clientId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("app client not found"));
            Set<String> groups = cognito.listGroups(builder -> builder.userPoolId(poolId).limit(60)).groups().stream()
                    .map(GroupType::groupName)
                    .collect(Collectors.toSet());
            GROUPS.stream().filter(group -> !groups.contains(group))
                    .forEach(group -> cognito.createGroup(builder -> builder.userPoolId(poolId).groupName(group)));
            try {
                cognito.adminGetUser(builder -> builder.userPoolId(poolId).username(USERNAME));
            } catch (UserNotFoundException missing) {
                cognito.adminCreateUser(builder -> builder.userPoolId(poolId).username(USERNAME)
                        .messageAction(MessageActionType.SUPPRESS));
            }
            cognito.adminSetUserPassword(builder -> builder.userPoolId(poolId).username(USERNAME)
                    .password(PASSWORD).permanent(true));
            GROUPS.forEach(group -> cognito.adminAddUserToGroup(builder -> builder.userPoolId(poolId)
                    .username(USERNAME).groupName(group)));
            return cognito.initiateAuth(builder -> builder.clientId(clientId)
                            .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                            .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)))
                    .authenticationResult().accessToken();
        }
    }

    private record Accepted(String quoteId, int version, String statusUrl) {

        /** Identidade devolvida pela API; a fase pode ter avançado entre um envio e outro. */
        String identity() {
            return quoteId + "#" + version + "#" + statusUrl;
        }
    }
}

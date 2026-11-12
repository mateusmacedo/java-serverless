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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.RestApi;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GroupType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolClientDescription;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolDescriptionType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Ponta a ponta do M1 ao M5 (§14) no Floci, com as esperas no orçamento do stage (§9). Cada caso usa um par
 * veículo/condutor próprio, porque a {@code queryKey} só depende dele (§5), e apaga o reuso do par (§10); os do M4
 * e do M5 apagam também, no {@code finally}, o estado que fabricaram e esperam os reconcilers pelo agendamento real.
 * Toda troca com a API da quote passa pelo {@link ContractClient}, que a confere contra o contrato (T22).
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
    /** {@code perm_offers_select} desde o M4: o T17 confere que só a versão atual oferece {@code SELECT_OFFER}. */
    private static final List<String> GROUPS =
            List.of("org_org-acme", "perm_quotes_read", "perm_quotes_write", "perm_offers_select");
    private static final User WRITER = new User(USERNAME, GROUPS);
    /** Operador da Acme com as cinco permissões do contrato. */
    private static final User OPERATOR = new User("e2e-acme-operator", List.of("org_org-acme", "perm_quotes_read",
            "perm_quotes_write", "perm_offers_select", "perm_operations_read", "perm_operations_reconcile"));
    /** Acme sem {@code offers:select} nem {@code operations:reconcile} (T30). */
    private static final User VIEWER = new User("e2e-acme-viewer",
            List.of("org_org-acme", "perm_quotes_read", "perm_quotes_write", "perm_operations_read"));
    /** Operador da Globex com as cinco permissões: o 404 dele vem da organização, não da permissão (T18). */
    private static final User GLOBEX = new User("e2e-globex-operator", List.of("org_org-globex", "perm_quotes_read",
            "perm_quotes_write", "perm_offers_select", "perm_operations_read", "perm_operations_reconcile"));
    private static final List<User> USERS = List.of(WRITER, OPERATOR, VIEWER, GLOBEX);

    private static final String APPLICANT = "customer-acme-frota";
    private static final String DRIVER = "driver-acme-bruno";
    private static final String DRIVER_WITHOUT_LICENSE_DATE = "driver-no-license";
    private static final String DRIVER_ANA = "driver-acme-ana";
    private static final String VAN = "vehicle-van-2022";
    private static final String SEDAN = "vehicle-sedan-2019";
    private static final String CLASSIC = "vehicle-classic-1998";
    private static final String SALVAGE = "vehicle-salvage";
    private static final String UNKNOWN_TO_THE_PROVIDER = "vehicle-not-found";
    private static final String ENRICHMENT_DOWN = "vehicle-enrichment-down";
    private static final String HATCH = "vehicle-hatch-2021";
    private static final String BETA_DECLINES = "vehicle-beta-declines";
    private static final String BETA_SILENT = "vehicle-beta-silent";
    private static final String BETA_DUPLICATE = "vehicle-beta-duplicate";
    private static final String BETA_EARLY = "vehicle-beta-early";
    private static final String BETA_LATE = "vehicle-beta-late";
    private static final String ALPHA_DECLINES = "vehicle-alpha-declines";
    private static final String ALPHA_DOWN = "vehicle-alpha-down";
    private static final String BETA_NEEDS_INFO = "vehicle-beta-needs-info";
    private static final String FLAKY = "vehicle-flaky";
    private static final String DRIVER_NOT_FOUND = "driver-not-found";
    private static final String GLOBEX_ORGANIZATION = "org-globex";
    private static final String GLOBEX_APPLICANT = "customer-globex-carla";
    private static final String GLOBEX_VEHICLE = "vehicle-globex-suv-2022";
    private static final String GLOBEX_DRIVER = "driver-globex-carla";
    private static final Path CATALOG = Path.of("../../contracts/fixtures/catalog.json");
    private static final Path FIXTURES = Path.of("../../contracts/fixtures");
    /** Campos internos que nenhuma resposta da API pode trazer (§12 do PRD). */
    private static final List<String> RAW_FIELDS = List.of("taskToken", "tokenCiphertext", "rawResponseRef",
            "rawRequestRef", "payload", "detail", "requestHash", "gsi1pk", "gsi2pk");
    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";
    private static final String CALLBACK_SECRET = "provider-solution/" + STAGE + "/callback-secret";
    private static final Path PROVIDER_SERVERLESS = Path.of("../../provider-solution/serverless.yml");
    private static final String EVENT_BUS = "platform-" + STAGE + "-events";
    private static final String RESULTS_QUEUE = QUOTE_PREFIX + "-results";
    private static final String SIM_CALLBACKS_QUEUE = PROVIDER_PREFIX + "-sim-callbacks";
    private static final String RESULTS_DLQ = QUOTE_PREFIX + "-results-dlq";
    private static final String INSURER_REQUESTS_QUEUE = PROVIDER_PREFIX + "-insurer-requests";
    private static final String RECONCILE_REQUESTS_QUEUE = PROVIDER_PREFIX + "-reconcile-requests";
    private static final String QUOTE_RECONCILER_LOG = "/aws/lambda/" + QUOTE_PREFIX + "-reconciler";
    private static final String PROVIDER_RECONCILER_LOG = "/aws/lambda/" + PROVIDER_PREFIX + "-reconciler";
    private static final String RESUME_LOG = "/aws/lambda/" + QUOTE_PREFIX + "-resume";
    private static final Path QUOTE_SERVERLESS = Path.of("../serverless.yml");
    /** Namespace dos UUIDv5 das duas soluções, o URL da RFC 9562 (§3). */
    private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    /** Token que a decifração da quote recusa: só serve para fazer a retomada cair depois de gravar (T13). */
    private static final String UNREADABLE_TOKEN = "e2e-t13-unreadable-token";
    /** T20: o dispatch da beta logo antes do ciclo seguinte do reconciler, que vem 60 ou 70 s depois (§13). */
    private static final Duration T20_DISPATCH_AFTER_CYCLE = Duration.ofSeconds(51);
    private static final int T20_ATTEMPTS = 3;
    /** Credencial padrão da provider ({@code PROVIDER_CREDENTIAL_REF}), parte da chave {@code QUOTA#} (§5). */
    private static final String CREDENTIAL_REF = "default";
    private static final DateTimeFormatter QUOTA_WINDOW =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
    /** Partes fixas da {@code queryKey} (§5), como a quote as envia no {@code EnrichmentRequested} (§3). */
    private static final List<String> ENRICHMENT_REQUEST = List.of("enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1");
    private static final String QUERY_KEY_SECRET = "provider-solution/" + STAGE + "/query-key-secret";
    private static final int CONCURRENT_QUOTES = Integer.getInteger("e2e.concurrentQuotes", 12);
    private static final String DECIMAL = "^\\d+(\\.\\d+)?$";
    private static final String ORGANIZATION = "org-acme";
    /** Dois disparos de {@code rate(1 minute)} e a publicação; o Floci dispara a cada 60 a 70 s. */
    private static final Duration RECONCILER_BUDGET = Duration.ofMinutes(3);
    /** Ida e volta da reconciliação manual: relay, barramento, filas e as duas Lambdas, sem agendamento. */
    private static final Duration RECONCILE_BUDGET = Duration.ofMinutes(1);
    /** Pedidos de cada lado por rodada do T29, e rodadas antes de selecionar sem disputa. */
    private static final int T29_CONTENDERS = 3;
    private static final int T29_ROUNDS = 3;
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Formato do {@code Timestamps} da provider, comparado como texto no {@code gsi2sk}. */
    private static final DateTimeFormatter PROVIDER_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final Logger LOG = Logger.getLogger(QuoteFlowE2E.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final DynamoDbClient DYNAMO_DB = DynamoDbClient.create();
    private static final SfnClient SFN = SfnClient.create();
    private static final S3Client S3 = S3Client.builder().forcePathStyle(true).build();
    private static final SqsClient SQS = SqsClient.create();
    private static final EventBridgeClient EVENT_BRIDGE = EventBridgeClient.create();
    private static final CloudWatchLogsClient LOGS = CloudWatchLogsClient.create();

    private static StageParameters stage;
    private static String apiBase;
    private static String providerApiBase;
    private static ContractClient api;
    private static final Map<User, String> TOKENS = new LinkedHashMap<>();
    private static String stateMachineArn;
    private static String desiredStartDate;
    private static byte[] queryKeySecret;
    private static byte[] callbackSecret;

    @BeforeAll
    static void locateTheStacksAndSignIn() {
        stage = StageParameters.load(Path.of("../serverless.yml"), STAGE);
        desiredStartDate = LocalDate.now(ZoneOffset.UTC).plusDays(30).toString();
        try (ApiGatewayClient apiGateway = ApiGatewayClient.create()) {
            apiBase = baseUrlOf(apiGateway, STAGE + "-quote-solution");
            providerApiBase = baseUrlOf(apiGateway, STAGE + "-provider-solution");
        }
        api = new ContractClient(apiBase);
        stateMachineArn = SFN.listStateMachinesPaginator(builder -> builder.maxResults(100)).stateMachines().stream()
                .filter(machine -> machine.name().equals(QUOTE_PREFIX + "-quote-flow"))
                .map(StateMachineListItem::stateMachineArn)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("state machine not found; deploy quote-solution first"));
        signIn();
        try (SecretsManagerClient secrets = SecretsManagerClient.create()) {
            queryKeySecret = secrets.getSecretValue(builder -> builder.secretId(QUERY_KEY_SECRET)).secretString()
                    .getBytes(StandardCharsets.UTF_8);
            callbackSecret = secrets.getSecretValue(builder -> builder.secretId(CALLBACK_SECRET)).secretString()
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void closeClients() {
        DYNAMO_DB.close();
        SFN.close();
        S3.close();
        SQS.close();
        EVENT_BRIDGE.close();
    }

    @Test
    void t01_commercialVanQuotesOnlyAtAlphaAndCompletesWithOneOfferAndEvidence() throws Exception {
        forgetEnrichmentReuse(VAN, DRIVER);
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

    /**
     * T04: a segunda cotação equivalente usa a evidência válida do índice de reuso ({@code HIT}). A provider não
     * abre consulta de enriquecimento para ela, e a evidência usada aponta a mesma evidência da provider, com a
     * política de reuso que a produziu.
     */
    @Test
    void t04_enrichmentReuseHitSkipsProviderCall() throws Exception {
        String queryKey = forgetEnrichmentReuse(SEDAN, DRIVER_ANA);
        String body = JSON.writeValueAsString(request(SEDAN, "COMMERCIAL", DRIVER_ANA));

        Accepted leader = create(newKey(), body);
        JsonNode leaderVersion = awaitTerminal(leader, stage.withInsurers(1));
        Set<String> queriesAfterTheLeader = queryIdsOf(enrichmentQueries(queryKey));
        Accepted reuser = create(newKey(), body);
        JsonNode reuserVersion = awaitTerminal(reuser, stage.withInsurers(1));

        assertThat(leaderVersion.path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(reuserVersion.path("outcome").asText()).isEqualTo("COMPLETED");
        Map<String, AttributeValue> led = enrichmentEvidenceUsed(leader.quoteId());
        Map<String, AttributeValue> reused = enrichmentEvidenceUsed(reuser.quoteId());
        assertThat(string(led, "reuseDecision")).isEqualTo("MISS");
        assertThat(string(reused, "reuseDecision")).isEqualTo("HIT");
        for (String attribute : List.of("evidenceId", "queryId", "resultType", "policyVersion")) {
            assertThat(string(reused, attribute)).as(attribute).isEqualTo(string(led, attribute));
        }
        String queryId = string(led, "queryId");
        Map<String, AttributeValue> query = providerQuery(queryId);
        assertThat(string(query, "quoteId")).isEqualTo(leader.quoteId());
        assertThat(string(query, "queryKey")).as("the harness derives the provider queryKey").isEqualTo(queryKey);
        Map<String, AttributeValue> evidence = single(providerItems(queryId, "EVIDENCE#" + string(led, "evidenceId")));
        assertThat(string(evidence, "policyVersion")).isEqualTo(string(led, "policyVersion"));
        assertThat(queriesAfterTheLeader).contains(queryId);
        assertThat(queryIdsOf(enrichmentQueries(queryKey))).as("no enrichment query for the HIT")
                .isEqualTo(queriesAfterTheLeader);
        assertThat(providerQueriesOf(reuser.quoteId(), "ENRICH")).isEmpty();
        logChain("T04 MISS", leader.quoteId());
        logChain("T04 HIT", reuser.quoteId());
    }

    /**
     * T05: com o {@code reusableUntil} do índice envelhecido pelo harness, a cotação equivalente abre outra consulta
     * na provider, numa liderança nova, e a consulta anterior, com tentativa e evidência, fica como estava.
     */
    @Test
    void t05_expiredEnrichmentStartsNewQuery() throws Exception {
        String queryKey = forgetEnrichmentReuse(CLASSIC, DRIVER_ANA);
        String body = JSON.writeValueAsString(request(CLASSIC, "PERSONAL", DRIVER_ANA));
        Accepted first = create(newKey(), body);
        awaitTerminal(first, stage.withInsurers(0));
        Map<String, AttributeValue> firstUsed = enrichmentEvidenceUsed(first.quoteId());
        String firstQueryId = string(firstUsed, "queryId");
        List<Map<String, AttributeValue>> firstHistory = providerHistory(firstQueryId);
        Map<String, AttributeValue> firstIndex = reuseIndex(queryKey);

        expireReuseIndex(queryKey, string(firstUsed, "evidenceId"));
        Accepted second = create(newKey(), body);
        JsonNode secondVersion = awaitTerminal(second, stage.withInsurers(0));

        assertThat(secondVersion.path("outcome").asText()).isEqualTo("NO_OFFER");
        assertThat(secondVersion.path("outcomeReason").asText()).isEqualTo("NO_APPLICABLE_INSURER");
        assertThat(string(firstUsed, "reuseDecision")).isEqualTo("MISS");
        assertThat(string(firstIndex, "evidenceId")).isEqualTo(string(firstUsed, "evidenceId"));
        Map<String, AttributeValue> secondUsed = enrichmentEvidenceUsed(second.quoteId());
        assertThat(string(secondUsed, "reuseDecision")).isEqualTo("MISS");
        assertThat(string(secondUsed, "queryId")).isNotEqualTo(firstQueryId);
        assertThat(string(secondUsed, "evidenceId")).isNotEqualTo(string(firstUsed, "evidenceId"));
        assertThat(providerHistory(firstQueryId)).as("the expired query, its attempt and its evidence")
                .containsExactlyInAnyOrderElementsOf(firstHistory);
        Map<String, AttributeValue> secondQuery = providerQuery(string(secondUsed, "queryId"));
        assertThat(string(secondQuery, "quoteId")).isEqualTo(second.quoteId());
        assertThat(number(secondQuery, "fencingVersion"))
                .isGreaterThan(number(providerQuery(firstQueryId), "fencingVersion"));
        Map<String, AttributeValue> index = reuseIndex(queryKey);
        assertThat(string(index, "evidenceId")).isEqualTo(string(secondUsed, "evidenceId"));
        assertThat(number(index, "revision")).isEqualTo(number(firstIndex, "revision") + 1);
        assertThat(Instant.parse(string(index, "reusableUntil"))).isAfter(Instant.now());
    }

    /**
     * T06 no Floci com {@code e2e.concurrentQuotes} cotações equivalentes disparadas juntas: um só pedido lidera a
     * consulta ({@code MISS}), os demais esperam por ela ({@code JOIN}) ou já encontram a evidência no índice
     * ({@code HIT}), e todos terminam com a mesma evidência, depois de uma única chamada ao provedor. NOTE: o ESM de
     * SQS do Floci entrega cerca de 1 mensagem por segundo, então aqui os demais tendem a {@code HIT} (§13), a espera
     * cresce 1 s por cotação, e um N que passe do prazo do enriquecimento termina em {@code TECHNICAL_FAILURE} por
     * limite do Floci, não por defeito de reuso.
     */
    @Test
    void t06_concurrentEquivalentQuotesShareOneLeader() throws Exception {
        String queryKey = forgetEnrichmentReuse(CLASSIC, DRIVER);
        Set<String> queriesBefore = queryIdsOf(enrichmentQueries(queryKey));
        String body = JSON.writeValueAsString(request(CLASSIC, "PERSONAL", DRIVER));
        Instant started = Instant.now();

        List<Accepted> accepted = new ArrayList<>();
        ExecutorService posts = Executors.newFixedThreadPool(CONCURRENT_QUOTES);
        try {
            List<Future<Accepted>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_QUOTES; i++) {
                futures.add(posts.submit(() -> create(newKey(), body)));
            }
            for (Future<Accepted> future : futures) {
                accepted.add(future.get());
            }
        } finally {
            posts.shutdownNow();
        }
        Instant allAccepted = Instant.now();
        Map<String, JsonNode> terminal = new LinkedHashMap<>();
        Duration budget = stage.withInsurers(0).plusSeconds(CONCURRENT_QUOTES);
        await().atMost(budget).pollInterval(stage.pollInterval()).until(() -> {
            for (Accepted quote : accepted) {
                if (!terminal.containsKey(quote.quoteId())) {
                    JsonNode version = getOk(quote.statusUrl());
                    if ("TERMINAL".equals(version.path("phase").asText())) {
                        terminal.put(quote.quoteId(), version);
                    }
                }
            }
            return terminal.size() == accepted.size();
        });
        Instant allTerminal = Instant.now();
        Map<String, Integer> outcomes = new TreeMap<>();
        terminal.values().forEach(version -> outcomes.merge(
                version.path("outcome").asText() + "/" + version.path("outcomeReason").asText(), 1, Integer::sum));

        assertThat(outcomes)
                .as("every version ends after the enrichment; TECHNICAL_FAILURE/ENRICHMENT_FAILED with N=%d usually means "
                        + "the ~1 msg/s SQS poller of Floci delivered the last requests after their deadlineAt (§13): "
                        + "lower -De2e.concurrentQuotes", CONCURRENT_QUOTES)
                .containsOnlyKeys("NO_OFFER/NO_APPLICABLE_INSURER");
        Map<String, Integer> decisions = new TreeMap<>();
        Set<String> evidenceIds = new HashSet<>();
        Set<String> queryIds = new HashSet<>();
        List<Chain> chains = new ArrayList<>();
        for (Accepted quote : accepted) {
            Map<String, AttributeValue> used = enrichmentEvidenceUsed(quote.quoteId());
            decisions.merge(string(used, "reuseDecision"), 1, Integer::sum);
            evidenceIds.add(string(used, "evidenceId"));
            queryIds.add(string(used, "queryId"));
            chains.add(chainOf(quote.quoteId()));
        }
        LOG.info(() -> "T06 N=%d decisions=%s posts=%d ms lastTerminalSeen=%d ms %s".formatted(CONCURRENT_QUOTES,
                decisions, Duration.between(started, allAccepted).toMillis(),
                Duration.between(started, allTerminal).toMillis(), Chain.summary(chains)));
        assertThat(decisions).as("one leader").containsEntry("MISS", 1);
        assertThat(decisions.keySet()).isSubsetOf("MISS", "JOIN", "HIT");
        assertThat(evidenceIds).as("every request ends with the leader evidence").hasSize(1);
        assertThat(queryIds).hasSize(1);
        Set<String> newQueries = new HashSet<>(queryIdsOf(enrichmentQueries(queryKey)));
        newQueries.removeAll(queriesBefore);
        assertThat(newQueries).as("one external enrichment query").isEqualTo(queryIds);
        String leaderQuery = queryIds.iterator().next();
        assertThat(providerItems(leaderQuery, "ATTEMPT#")).as("one call, without failures").hasSize(1);
        assertThat(string(providerQuery(leaderQuery), "state")).isEqualTo("COMPLETED");
    }

    @Test
    void t25_enrichmentNotFoundNeedsInformation() throws Exception {
        Accepted accepted = create(newKey(),
                JSON.writeValueAsString(request(UNKNOWN_TO_THE_PROVIDER, "COMMERCIAL", DRIVER)));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(0));

        assertThat(version.path("outcome").asText()).isEqualTo("NEEDS_INFORMATION");
        assertThat(version.path("outcomeReason").asText()).isEqualTo("ENRICHMENT_NOT_FOUND");
        JsonNode missingFields = version.path("missingFields");
        assertThat(missingFields).hasSize(1);
        assertThat(missingFields.get(0).path("path").asText()).isEqualTo("risk.vehicleRef");
        assertThat(missingFields.get(0).path("reasonCode").asText()).isEqualTo("ENRICHMENT_NOT_FOUND");
        assertThat(missingFields.get(0).path("label").asText()).isNotBlank();
        assertThat(version.path("insurerResults")).isEmpty();
        Map<String, AttributeValue> used = enrichmentEvidenceUsed(accepted.quoteId());
        assertThat(string(used, "resultType")).isEqualTo("NOT_FOUND");
        assertThat(string(used, "reuseDecision")).isIn("MISS", "HIT");
        assertThat(string(providerQuery(string(used, "queryId")), "state")).isEqualTo("COMPLETED");
        Map<String, AttributeValue> index = reuseIndex(queryKey(UNKNOWN_TO_THE_PROVIDER, DRIVER));
        assertThat(string(index, "resultType")).as("NOT_FOUND declared by the provider is reusable")
                .isEqualTo("NOT_FOUND");
        for (String attribute : List.of("evidenceId", "queryId", "policyVersion")) {
            assertThat(string(index, attribute)).as(attribute).isEqualTo(string(used, attribute));
        }
        assertNoInsurerConsulted(accepted.quoteId());
    }

    @Test
    void t27_enrichmentFailureIsTechnicalFailure() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(ENRICHMENT_DOWN, "COMMERCIAL", DRIVER)));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(0));

        assertThat(version.path("outcome").asText()).isEqualTo("TECHNICAL_FAILURE");
        assertThat(version.path("outcomeReason").asText()).isEqualTo("ENRICHMENT_FAILED");
        assertThat(version.path("missingFields")).isEmpty();
        assertThat(version.path("insurerResults")).isEmpty();
        Map<String, AttributeValue> used = enrichmentEvidenceUsed(accepted.quoteId());
        assertThat(string(used, "resultType")).isEqualTo("FAILED");
        assertThat(string(used, "reuseDecision")).isEqualTo("MISS");
        assertThat(queryByPrefix(QUERIES_TABLE, "REUSE#" + queryKey(ENRICHMENT_DOWN, DRIVER), "META"))
                .as("FAILED never enters the reuse index").isEmpty();
        JsonNode result = JSON.readTree(string(used, "summary")).path("result");
        assertThat(result.isMissingNode() || result.isNull()).as("no invented profile").isTrue();
        String queryId = string(used, "queryId");
        assertThat(string(providerQuery(queryId), "quoteId")).isEqualTo(accepted.quoteId());
        assertThat(string(providerQuery(queryId), "state")).isEqualTo("FAILED");
        Map<String, AttributeValue> evidence = single(providerItems(queryId, "EVIDENCE#"));
        assertThat(string(evidence, "resultType")).isEqualTo("FAILED");
        assertThat(evidence).doesNotContainKey("normalizedResult");
        assertNoInsurerConsulted(accepted.quoteId());
    }

    /**
     * T01 com as duas seguradoras: a alpha responde na chamada e a beta por callback. Cada oferta aponta a consulta e
     * a evidência da provider, e o callback da beta fica processado na inbox.
     */
    @Test
    void t01_twoInsurersQuoteAndEveryOfferLinksItsEvidence() throws Exception {
        forgetEnrichmentReuse(HATCH, DRIVER_ANA);
        Instant posted = Instant.now();
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(HATCH, "PERSONAL", DRIVER_ANA)));
        awaitFirstOffer(accepted, stage.withInsurers(2));
        Duration firstOffer = Duration.between(posted, Instant.now());
        LOG.info(() -> "T01 first offer visible %d ms after the POST: quoteId=%s".formatted(firstOffer.toMillis(),
                accepted.quoteId()));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));

        assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(version.path("outcomeReason").isNull()).isTrue();
        assertThat(version.path("missingFields")).isEmpty();
        assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "QUOTED"));
        Map<String, String> names = namesByInsurer(version);
        assertThat(names).containsEntry(ALPHA, "Alpha Seguros").containsEntry(BETA, "Beta Seguros");
        Map<String, JsonNode> offers = offersByInsurer(accepted);
        assertThat(offers).containsOnlyKeys(ALPHA, BETA);
        for (JsonNode offer : offers.values()) {
            assertThat(offer.path("insurerName").asText()).isEqualTo(names.get(offer.path("insurerId").asText()));
            assertThat(offer.path("premiumTotal").asText()).matches(DECIMAL);
            assertThat(offer.path("coverages")).isNotEmpty();
            assertThat(offer.path("selectable").asBoolean()).isTrue();
            assertInsurerEvidence(accepted.quoteId(), offer.path("id").asText());
        }
        assertEnrichmentEvidence(accepted.quoteId());
        String betaQuery = string(insurerQuery(accepted.quoteId(), BETA), "queryId");
        Map<String, AttributeValue> callback = single(inboxOf(betaQuery));
        assertThat(string(callback, "processingStatus")).isEqualTo("PROCESSED");
        assertThat(string(single(providerItems(betaQuery, "EVIDENCE#")), "rawResponseRef"))
                .as("the beta evidence keeps the callback").contains(string(callback, "externalEventId"));
        assertThat(awaitSingleExecution(accepted.quoteId()).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        logInsurerChain("T01", accepted.quoteId());
    }

    /** T07: a beta recusa e a alpha oferece; a versão conclui com uma oferta e o motivo da recusa da beta. */
    @Test
    void t07_insurerDeclinesAndTheOtherOfferCompletesTheQuote() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(BETA_DECLINES, "PERSONAL", DRIVER)));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));

        assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(version.path("outcomeReason").isNull()).isTrue();
        assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "DECLINED"));
        assertThat(insurerResult(version, BETA).path("reasonCode").asText()).as("the decline reason of the beta")
                .isEqualTo("RISK_NOT_ACCEPTED");
        assertThat(offersByInsurer(accepted)).containsOnlyKeys(ALPHA);
        String betaQuery = string(insurerQuery(accepted.quoteId(), BETA), "queryId");
        assertThat(string(providerQuery(betaQuery), "state")).isEqualTo("COMPLETED");
        assertThat(string(single(providerItems(betaQuery, "EVIDENCE#")), "resultType")).isEqualTo("DECLINED");
        assertThat(string(single(quoteItems(accepted.quoteId(), "V#0001#RESULT#" + BETA)), "queryRef"))
                .isEqualTo(betaQuery);
    }

    /**
     * T08: a beta aceita o pedido e não responde ({@code vehicle-beta-silent}). A oferta da alpha aparece no GET
     * enquanto a beta está em processamento, e a versão termina {@code PARTIAL}, com a mesma oferta.
     */
    @Test
    void t08_offerIsVisibleBeforeTheOtherInsurerTimesOut() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(BETA_SILENT, "PERSONAL", DRIVER)));
        Duration budget = stage.withInsurers(2);

        JsonNode quoting = await().atMost(budget).pollInterval(stage.pollInterval())
                .until(() -> getOk(accepted.statusUrl()), found -> "QUOTED".equals(statusByInsurer(found).get(ALPHA)));
        Map<String, JsonNode> offersWhileQuoting = offersByInsurer(accepted);
        JsonNode version = awaitTerminal(accepted, budget);

        assertThat(quoting.path("phase").asText()).as("the alpha offer is visible before the consolidation")
                .isEqualTo("QUOTING");
        assertThat(statusByInsurer(quoting)).containsEntry(BETA, "PROCESSING");
        assertThat(offersWhileQuoting).containsOnlyKeys(ALPHA);
        assertThat(version.path("outcome").asText()).isEqualTo("PARTIAL");
        assertThat(version.path("outcomeReason").isNull()).isTrue();
        assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "TIMED_OUT"));
        assertThat(insurerResult(version, BETA).path("reasonCode").asText()).isEqualTo("STEP_TIMEOUT");
        Map<String, JsonNode> offers = offersByInsurer(accepted);
        assertThat(offers).containsOnlyKeys(ALPHA);
        assertThat(offers.get(ALPHA).path("id").asText()).isEqualTo(offersWhileQuoting.get(ALPHA).path("id").asText());
        Map<String, AttributeValue> betaQuery = insurerQuery(accepted.quoteId(), BETA);
        assertThat(string(betaQuery, "providerRequestId")).as("the beta accepted the request").isNotBlank();
        assertThat(string(betaQuery, "state")).as("left for the reconciliation (M4)").isIn("IN_FLIGHT", "UNKNOWN");
        assertThat(providerItems(string(betaQuery, "queryId"), "EVIDENCE#")).isEmpty();
    }

    /**
     * T11: a beta entrega o mesmo callback duas vezes ({@code vehicle-beta-duplicate}); depois da conclusão, o
     * harness reenvia o callback assinado e publica de novo o {@code InsurerResultRecorded}. A inbox reconhece a
     * repetição, a retomada descarta o evento repetido (o descarte só deixa a linha de log com o {@code eventId}),
     * e nada muda na cotação nem na consulta.
     */
    @Test
    void t11_duplicateCallbackYieldsOneOffer() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(BETA_DUPLICATE, "PERSONAL", DRIVER)));
        JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));
        awaitDrained(SIM_CALLBACKS_QUEUE);
        String betaQuery = string(insurerQuery(accepted.quoteId(), BETA), "queryId");
        Map<String, AttributeValue> callback = single(inboxOf(betaQuery));
        List<Map<String, AttributeValue>> versionBefore = quoteItems(accepted.quoteId(), "V#0001");
        List<Map<String, AttributeValue>> queryBefore = providerHistory(betaQuery);
        String eventId = string(waiterOf(accepted.quoteId(), BETA), "resultEventId");

        HttpResponse<String> resent = postCallback(BETA, string(callback, "payload"));
        Instant republishedAt = Instant.now();
        republish(single(queryByPrefix(QUERIES_TABLE, "OUTBOX#" + eventId, "META")));
        awaitDiscarded(eventId, republishedAt);
        awaitDrained(RESULTS_QUEUE);

        assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "QUOTED"));
        assertThat(string(callback, "processingStatus")).isEqualTo("PROCESSED");
        assertThat(resent.statusCode()).as(resent.body()).isEqualTo(200);
        JsonNode receipt = JSON.readTree(resent.body());
        assertThat(receipt.path("duplicate").asBoolean()).isTrue();
        assertThat(receipt.path("processingStatus").asText()).isEqualTo("PROCESSED");
        assertThat(offersByInsurer(accepted)).containsOnlyKeys(ALPHA, BETA);
        assertThat(quoteItems(accepted.quoteId(), "V#0001")).as("no late or duplicate result and no other offer")
                .containsExactlyInAnyOrderElementsOf(versionBefore);
        assertThat(providerHistory(betaQuery)).containsExactlyInAnyOrderElementsOf(queryBefore);
        assertThat(inboxOf(betaQuery)).containsExactly(callback);
        assertThat(string(waiterOf(accepted.quoteId(), BETA), "status")).isEqualTo("DONE");
        assertThat(queryByPrefix(QUOTES_TABLE, "CONSUMED#" + eventId, "META")).hasSize(1);
    }

    /**
     * T12: a beta agenda o callback sem atraso e só com o {@code providerRequestId} ({@code vehicle-beta-early}); ele
     * só é associado pelo encontro no {@code CORR#} (§8), e a oferta chega sem perda. NOTE: o ESM do Floci entrega o
     * callback cerca de 1 s depois, já depois do {@code 202}; a ordem inversa fica na integração da provider.
     */
    @Test
    void t12_callbackBeforeCorrelationIsNotLost() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(BETA_EARLY, "PERSONAL", DRIVER)));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));

        assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "QUOTED"));
        assertThat(offersByInsurer(accepted)).containsOnlyKeys(ALPHA, BETA);
        Map<String, AttributeValue> query = insurerQuery(accepted.quoteId(), BETA);
        String queryId = string(query, "queryId");
        String providerRequestId = string(query, "providerRequestId");
        assertThat(string(query, "state")).isEqualTo("COMPLETED");
        Map<String, AttributeValue> callback = single(inboxOf(queryId));
        assertThat(string(callback, "processingStatus")).isEqualTo("PROCESSED");
        assertThat(callback).as("the early callback carries only the providerRequestId")
                .doesNotContainKey("clientReference");
        assertThat(string(callback, "providerRequestId")).isEqualTo(providerRequestId);
        Map<String, AttributeValue> correlation =
                single(queryByPrefix(QUERIES_TABLE, "CORR#" + BETA + "#" + providerRequestId, "META"));
        assertThat(string(correlation, "queryId")).as("the worker half").isEqualTo(queryId);
        assertThat(correlation.get("pendingEvents").ss()).as("the API half")
                .contains(string(callback, "externalEventId"));
        logInsurerChain("T12", accepted.quoteId());
    }

    /**
     * T21: a quota da alpha é uma só para todos os workflows (§9). Com a janela corrente reservada até sobrarem
     * {@code granted} chamadas e a seguinte esgotada, as cotações simultâneas fazem só essas chamadas; as demais
     * terminam sem chamada, com {@code QUOTA_EXHAUSTED}, e nenhuma janela passa do limite.
     */
    @Test
    void t21_insurerQuotaIsGlobalAcrossWorkflows() throws Exception {
        int limit = StageParameters.number(PROVIDER_SERVERLESS, STAGE, "alphaQuotaPerMinute");
        int granted = 2;
        int quotes = 5;
        Instant window = awaitFreshQuotaWindow();
        Instant next = window.plusSeconds(60);
        try {
            putQuota(ALPHA, window, limit - granted);
            putQuota(ALPHA, next, limit);
            String body = JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER));
            List<Accepted> accepted = new ArrayList<>();
            for (int i = 0; i < quotes; i++) {
                accepted.add(create(newKey(), body));
            }
            Map<String, Integer> outcomes = new TreeMap<>();
            int calls = 0;
            for (Accepted quote : accepted) {
                JsonNode version = awaitTerminal(quote, stage.withInsurers(1).plusSeconds(quotes));
                JsonNode alpha = insurerResult(version, ALPHA);
                outcomes.merge("%s/%s alpha=%s/%s".formatted(version.path("outcome").asText(),
                        version.path("outcomeReason").asText(), alpha.path("status").asText(),
                        alpha.path("reasonCode").asText()), 1, Integer::sum);
                calls += providerItems(string(insurerQuery(quote.quoteId(), ALPHA), "queryId"), "ATTEMPT#").size();
            }

            assertThat(outcomes).containsOnly(
                    Map.entry("COMPLETED/null alpha=QUOTED/null", granted),
                    Map.entry("TECHNICAL_FAILURE/INSURER_TECHNICAL_FAILURE alpha=UNAVAILABLE/QUOTA_EXHAUSTED",
                            quotes - granted));
            assertThat(calls).as("calls to the alpha, one per granted reservation").isEqualTo(granted);
            assertThat(quotaUsed(ALPHA, window)).as("the window ends exactly at the limit").isEqualTo(limit);
            assertThat(quotaUsed(ALPHA, next)).isEqualTo(limit);
        } finally {
            deleteQuota(ALPHA, window);
            deleteQuota(ALPHA, next);
        }
    }

    /**
     * T26: o veículo anterior a 2000 não tem seguradora aplicável (§10): {@code NO_OFFER}, sem pedido, com a
     * seleção feita depois do enriquecimento. NOTE: no {@code vehicle-classic-1998} o ano do catálogo e o do
     * enriquecimento coincidem; que a regra usa o ano enriquecido, e não o de entrada, é provado no
     * {@code QuoteFunctionsFlociIT} ({@code selectionUsesTheEnrichedVehicleYearInsteadOfTheInputYear}).
     */
    @Test
    void t26_noApplicableInsurerEndsWithoutOffers() throws Exception {
        Accepted accepted = create(newKey(), JSON.writeValueAsString(request(CLASSIC, "PERSONAL", DRIVER_ANA)));

        JsonNode version = awaitTerminal(accepted, stage.withInsurers(0));

        assertThat(version.path("outcome").asText()).isEqualTo("NO_OFFER");
        assertThat(version.path("outcomeReason").asText()).isEqualTo("NO_APPLICABLE_INSURER");
        assertThat(version.path("missingFields")).isEmpty();
        assertThat(version.path("insurerResults")).isEmpty();
        assertThat(getOk(accepted.statusUrl() + "/offers").path("items")).isEmpty();
        assertThat(string(enrichmentEvidenceUsed(accepted.quoteId()), "resultType")).isEqualTo("FOUND");
        Map<String, AttributeValue> stored = versionItem(accepted.quoteId());
        assertThat(stored).containsKey("selectedInsurers");
        assertThat(stored.get("selectedInsurers").l()).as("the selection rule ran and chose no insurer").isEmpty();
        assertThat(string(stored, "selectionRuleVersion")).isNotBlank();
        assertNoInsurerConsulted(accepted.quoteId());
    }

    /**
     * T09: com uso {@code COMMERCIAL} só a alpha é aplicável, e ela recusa ({@code vehicle-alpha-declines}):
     * {@code NO_OFFER} com {@code ALL_INSURERS_DECLINED}, depois de uma única chamada, sem retry comercial.
     */
    @Test
    void t09_everyApplicableInsurerDeclinesEndsWithNoOfferAfterOneCall() throws Exception {
        // NOTE: nenhum veículo faz a alpha e a beta recusarem juntas (§10); a recusa da beta ao lado da oferta da
        // alpha é o T07
        forgetEnrichmentReuse(ALPHA_DECLINES, DRIVER);
        try {
            Accepted accepted = create(newKey(),
                    JSON.writeValueAsString(request(ALPHA_DECLINES, "COMMERCIAL", DRIVER)));

            JsonNode version = awaitTerminal(accepted, stage.withInsurers(1));

            assertThat(version.path("outcome").asText()).isEqualTo("NO_OFFER");
            assertThat(version.path("outcomeReason").asText()).isEqualTo("ALL_INSURERS_DECLINED");
            assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "DECLINED"));
            assertThat(insurerResult(version, ALPHA).path("reasonCode").asText()).isEqualTo("RISK_NOT_ACCEPTED");
            assertThat(getOk(accepted.statusUrl() + "/offers").path("items")).isEmpty();
            String queryId = string(insurerQuery(accepted.quoteId(), ALPHA), "queryId");
            assertThat(string(providerQuery(queryId), "state")).isEqualTo("COMPLETED");
            assertThat(providerItems(queryId, "ATTEMPT#")).as("a decline is final: no commercial retry").hasSize(1);
            assertThat(string(single(providerItems(queryId, "EVIDENCE#")), "resultType")).isEqualTo("DECLINED");
        } finally {
            forgetEnrichmentReuse(ALPHA_DECLINES, DRIVER);
        }
    }

    /**
     * T10: a única seguradora aplicável está fora do ar ({@code vehicle-alpha-down}, 503): {@code TECHNICAL_FAILURE}
     * com {@code INSURER_TECHNICAL_FAILURE}, nunca {@code NO_OFFER}, depois da chamada e dos {@code workerRetries}.
     */
    @Test
    void t10_everyApplicableInsurerUnavailableIsTechnicalFailureAndNotNoOffer() throws Exception {
        int workerRetries = StageParameters.number(PROVIDER_SERVERLESS, STAGE, "workerRetries");
        forgetEnrichmentReuse(ALPHA_DOWN, DRIVER);
        try {
            Accepted accepted = create(newKey(), JSON.writeValueAsString(request(ALPHA_DOWN, "COMMERCIAL", DRIVER)));

            JsonNode version = awaitTerminal(accepted, stage.withInsurers(1));

            assertThat(version.path("outcome").asText()).isEqualTo("TECHNICAL_FAILURE");
            assertThat(version.path("outcomeReason").asText()).isEqualTo("INSURER_TECHNICAL_FAILURE");
            assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "UNAVAILABLE"));
            assertThat(getOk(accepted.statusUrl() + "/offers").path("items")).isEmpty();
            String queryId = string(insurerQuery(accepted.quoteId(), ALPHA), "queryId");
            assertThat(string(providerQuery(queryId), "state")).isEqualTo("FAILED");
            assertThat(providerItems(queryId, "ATTEMPT#")).as("the call and the technical retries of the worker (§9)")
                    .hasSize(1 + workerRetries);
            assertThat(string(single(providerItems(queryId, "EVIDENCE#")), "resultType")).isEqualTo("UNAVAILABLE");
        } finally {
            forgetEnrichmentReuse(ALPHA_DOWN, DRIVER);
        }
    }

    /**
     * T13: o {@code resume} grava o resultado da beta e cai antes do {@code SendTaskSuccess}; a versão termina com o
     * resultado gravado, o {@code reconciler} retoma o waiter parado em {@code RESUMING} e nenhuma chamada se repete.
     */
    @Test
    void t13_savedResultWhoseResumeFailedIsRecoveredWithoutCallingTheInsurerAgain() throws Exception {
        // NOTE: estado fabricado, como o QUOTA# do T21: o token do waiter da beta fica ilegível enquanto ele espera,
        // então o resume grava RESUMING, resumePayload, RESULT, OFFER e CONSUMED e cai na decifração. No local, a
        // espera da beta (20 s) acaba antes do resumingRetryAfterSeconds (30 s): a consolidação usa o resultado
        // gravado, e a retomada do reconciler termina EXPIRED com o resultado entregue, sem TASK_EXPIRED (§8)
        forgetEnrichmentReuse(HATCH, DRIVER);
        Map<String, AttributeValue> waiterKey = null;
        String originalToken = null;
        try {
            Accepted accepted = create(newKey(), JSON.writeValueAsString(request(HATCH, "PERSONAL", DRIVER)));
            String workRef = workRef(accepted.quoteId(), 1, "INSURER", BETA);
            Map<String, AttributeValue> key = metaKey("WAITER#" + workRef);
            waiterKey = key;
            Map<String, AttributeValue> waiting = await().atMost(stage.withInsurers(2))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> item(QUOTES_TABLE, key), found -> "WAITING".equals(stringOrNull(found, "status")));
            originalToken = string(waiting, "tokenCiphertext");
            replaceToken(key, originalToken, UNREADABLE_TOKEN);
            Map<String, AttributeValue> resuming = await().atMost(stage.withInsurers(2))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> item(QUOTES_TABLE, key), found -> "RESUMING".equals(stringOrNull(found, "status")));
            Instant resumingAt = Instant.parse(string(resuming, "updatedAt"));
            String eventId = string(resuming, "resultEventId");
            Map<String, AttributeValue> savedResult = single(quoteItems(accepted.quoteId(), "V#0001#RESULT#" + BETA));
            List<Map<String, AttributeValue>> savedOffers = quoteItems(accepted.quoteId(), "V#0001#OFFER#");
            // NOTE: a reentrega da fila results só vem depois do expiresAt do waiter e é descartada pelo CONSUMED,
            // sem SendTaskSuccess; quem retoma o RESUMING é o reconciler
            awaitLogEvent(RESUME_LOG, "failed and will be redelivered", resumingAt.minusSeconds(1),
                    Duration.ofSeconds(60));
            replaceToken(key, UNREADABLE_TOKEN, originalToken);
            originalToken = null;
            Instant restoredAt = Instant.now();

            JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));
            awaitLog(QUOTE_RECONCILER_LOG, "reconciler resumed workRef=" + workRef, restoredAt, RECONCILER_BUDGET);
            Map<String, AttributeValue> waiter = await().atMost(RECONCILER_BUDGET).pollInterval(Duration.ofSeconds(5))
                    .until(() -> item(QUOTES_TABLE, key), found -> !found.containsKey("gsi2pk"));

            assertThat(string(resuming, "resumePayload")).contains("QUOTED");
            assertThat(string(savedResult, "status")).isEqualTo("QUOTED");
            assertThat(string(savedResult, "resultEventId")).isEqualTo(eventId);
            assertThat(queryByPrefix(QUOTES_TABLE, "CONSUMED#" + eventId, "META")).hasSize(1);
            assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
            assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "QUOTED"));
            assertThat(offersByInsurer(accepted)).containsOnlyKeys(ALPHA, BETA);
            assertThat(quoteItems(accepted.quoteId(), "V#0001#OFFER#")).as("the offers saved before the failure")
                    .containsExactlyInAnyOrderElementsOf(savedOffers);
            assertThat(single(quoteItems(accepted.quoteId(), "V#0001#RESULT#" + BETA))).isEqualTo(savedResult);
            assertThat(quoteItems(accepted.quoteId(), "V#0001#LATE#")).isEmpty();
            assertThat(string(waiter, "status")).as("left RESUMING with the stored result").isIn("DONE", "EXPIRED");
            assertThat(string(waiter, "resultEventId")).isEqualTo(eventId);
            assertThat(quoteIssue("TASK_EXPIRED", workRef)).as("the result was delivered").isEmpty();
            for (String insurerId : List.of(ALPHA, BETA)) {
                String queryId = string(insurerQuery(accepted.quoteId(), insurerId), "queryId");
                assertThat(providerItems(queryId, "ATTEMPT#")).as("one call to %s", insurerId).hasSize(1);
            }
        } finally {
            if (originalToken != null) {
                restoreToken(waiterKey, originalToken);
            }
            forgetEnrichmentReuse(HATCH, DRIVER);
        }
    }

    /**
     * T14: a beta aceita e nunca responde ({@code vehicle-beta-silent}). Depois do prazo, o {@code reconciler} da
     * provider deixa a consulta {@code UNKNOWN} e a conclui pelo GET de situação, sem nova chamada; o resultado chega
     * à quote como evidência tardia, e a cotação terminal não reabre.
     */
    @Test
    void t14_silentInsurerIsReconciledByTheStatusApiWithoutRepeatingTheCall() throws Exception {
        forgetEnrichmentReuse(BETA_SILENT, DRIVER_ANA);
        try {
            Accepted accepted = create(newKey(), JSON.writeValueAsString(request(BETA_SILENT, "PERSONAL", DRIVER_ANA)));
            JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));
            Instant terminalAt = Instant.now();
            Map<String, JsonNode> offers = offersByInsurer(accepted);
            List<Map<String, AttributeValue>> versionBefore = quoteItems(accepted.quoteId(), "V#0001");
            String betaQuery = string(insurerQuery(accepted.quoteId(), BETA), "queryId");

            Map<String, AttributeValue> late = awaitLate(accepted.quoteId(), BETA, betaQuery);

            assertThat(version.path("outcome").asText()).isEqualTo("PARTIAL");
            assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "TIMED_OUT"));
            assertLog(PROVIDER_RECONCILER_LOG, "left UNKNOWN: queryId=" + betaQuery, terminalAt.minusSeconds(60));
            assertLog(PROVIDER_RECONCILER_LOG, "concluded by the status API, without a new call: queryId=" + betaQuery,
                    terminalAt.minusSeconds(60));
            assertThat(string(providerQuery(betaQuery), "state")).isEqualTo("COMPLETED");
            assertThat(providerItems(betaQuery, "ATTEMPT#")).as("the quote request is never repeated").hasSize(1);
            assertThat(inboxOf(betaQuery)).as("the beta never called back").isEmpty();
            Map<String, AttributeValue> evidence = single(providerItems(betaQuery, "EVIDENCE#"));
            assertThat(string(evidence, "resultType")).isEqualTo("QUOTED");
            String rawResponseRef = string(evidence, "rawResponseRef");
            assertThat(rawResponseRef).isEqualTo("raw/" + betaQuery + "/status-0001.json");
            assertThat(S3.headObject(builder -> builder.bucket(PAYLOADS_BUCKET).key(rawResponseRef)).contentLength())
                    .isPositive();
            assertThat(string(late, "reason")).isEqualTo("LATE");
            assertThat(string(late, "status")).isEqualTo("QUOTED");
            assertThat(string(late, "queryRef")).isEqualTo(betaQuery);
            assertTerminalUnchanged(accepted, version, offers, versionBefore);
            providerIssue("UNKNOWN_QUERY", betaQuery).ifPresent(issue -> assertThat(string(issue, "status"))
                    .as("an UNKNOWN_QUERY raised before the status API answered is resolved").isEqualTo("RESOLVED"));
        } finally {
            forgetEnrichmentReuse(BETA_SILENT, DRIVER_ANA);
        }
    }

    /**
     * T16: o callback da beta chega depois do prazo final da espera ({@code vehicle-beta-late}): a versão termina
     * {@code PARTIAL}, o resultado entra como evidência tardia ({@code LATE}), e a versão, o resumo e as ofertas não
     * mudam.
     */
    @Test
    void t16_callbackAfterTheFinalDeadlineIsKeptAsLateEvidenceAndTheQuoteDoesNotReopen() throws Exception {
        forgetEnrichmentReuse(BETA_LATE, DRIVER);
        try {
            Accepted accepted = create(newKey(), JSON.writeValueAsString(request(BETA_LATE, "PERSONAL", DRIVER)));
            JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));
            Map<String, JsonNode> offers = offersByInsurer(accepted);
            List<Map<String, AttributeValue>> versionBefore = quoteItems(accepted.quoteId(), "V#0001");
            String betaQuery = string(insurerQuery(accepted.quoteId(), BETA), "queryId");

            Map<String, AttributeValue> late = awaitLate(accepted.quoteId(), BETA, betaQuery);

            assertThat(version.path("outcome").asText()).isEqualTo("PARTIAL");
            assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "TIMED_OUT"));
            assertThat(insurerResult(version, BETA).path("reasonCode").asText()).isEqualTo("STEP_TIMEOUT");
            assertThat(string(late, "reason")).isEqualTo("LATE");
            assertThat(string(late, "status")).isEqualTo("QUOTED");
            assertThat(string(late, "rawResponseRef")).startsWith("raw/" + betaQuery + "/");
            assertThat(string(providerQuery(betaQuery), "state")).isEqualTo("COMPLETED");
            assertThat(providerItems(betaQuery, "ATTEMPT#")).hasSize(1);
            assertThat(providerItems(betaQuery, "EVIDENCE#")).as("the late callback is the only evidence").hasSize(1);
            assertTerminalUnchanged(accepted, version, offers, versionBefore);
            LOG.info(() -> "T16 quoteId=%s late evidence from %s".formatted(accepted.quoteId(),
                    string(late, "rawResponseRef").contains("/status-") ? "the status API" : "the callback"));
        } finally {
            forgetEnrichmentReuse(BETA_LATE, DRIVER);
        }
    }

    /**
     * T17: a versão 2 é criada enquanto a versão 1 ainda consulta. Os resultados da versão 1 chegam depois da troca e
     * ficam nela; a versão substituída fica sem ações e com as ofertas não selecionáveis, e só a atual oferece
     * {@code SELECT_OFFER}. O reenvio da chave devolve a mesma versão e a versão lida antiga recebe 409.
     */
    @Test
    void t17_newVersionDuringTheOldQueryKeepsResultsApartAndBlocksSelectionOnTheOldVersion() throws Exception {
        forgetEnrichmentReuse(SEDAN, DRIVER);
        forgetEnrichmentReuse(BETA_DECLINES, DRIVER_ANA);
        try {
            Accepted first = create(newKey(), JSON.writeValueAsString(request(SEDAN, "PERSONAL", DRIVER)));
            String versionKey = newKey();
            String versionBody = JSON.writeValueAsString(versionRequest(1, BETA_DECLINES, "PERSONAL", DRIVER_ANA));
            HttpResponse<String> created = postVersion(first.quoteId(), versionKey, versionBody);
            JsonNode firstWhenReplaced = getOk(first.statusUrl());
            HttpResponse<String> replayed = postVersion(first.quoteId(), versionKey, versionBody);
            HttpResponse<String> stale = postVersion(first.quoteId(), newKey(), versionBody);

            assertThat(created.statusCode()).as(created.body()).isEqualTo(202);
            Accepted second = accepted(created);
            JsonNode oldVersion = awaitTerminal(first, stage.withInsurers(2));
            JsonNode newVersion = awaitTerminal(second, stage.withInsurers(2));

            assertThat(second.quoteId()).isEqualTo(first.quoteId());
            assertThat(second.version()).isEqualTo(2);
            assertThat(second.statusUrl()).isEqualTo("/v1/quotes/" + first.quoteId() + "/versions/2");
            assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(202);
            assertThat(accepted(replayed).identity()).isEqualTo(second.identity());
            assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
            assertThat(JSON.readTree(stale.body()).path("code").asText()).isEqualTo("VERSION_CONFLICT");
            assertThat(firstWhenReplaced.path("phase").asText()).as("the version 1 was still running")
                    .isNotEqualTo("TERMINAL");
            assertThat(firstWhenReplaced.path("supersededAt").asText()).isNotBlank();

            assertThat(oldVersion.path("outcome").asText()).isEqualTo("COMPLETED");
            assertThat(oldVersion.path("currentVersion").asInt()).isEqualTo(2);
            assertThat(oldVersion.path("supersededAt").asText()).isNotBlank();
            assertThat(oldVersion.path("allowedActions")).as("no SELECT_OFFER on the superseded version").isEmpty();
            assertThat(oldVersion.at("/snapshot/risk/vehicleRef").asText()).isEqualTo(SEDAN);
            assertThat(statusByInsurer(oldVersion)).containsExactly(Map.entry(ALPHA, "QUOTED"), Map.entry(BETA, "QUOTED"));
            Map<String, JsonNode> oldOffers = offersByInsurer(first);
            assertThat(oldOffers).containsOnlyKeys(ALPHA, BETA);
            assertThat(oldOffers.values()).allSatisfy(offer -> {
                assertThat(offer.path("selectable").asBoolean()).isFalse();
                assertThat(offer.path("unavailabilityReason").asText()).isEqualTo("VERSION_SUPERSEDED");
            });

            assertThat(newVersion.path("outcome").asText()).isEqualTo("COMPLETED");
            assertThat(newVersion.path("supersededAt").isNull()).isTrue();
            assertThat(newVersion.at("/snapshot/risk/vehicleRef").asText()).isEqualTo(BETA_DECLINES);
            assertThat(texts(newVersion.path("allowedActions"))).containsExactlyInAnyOrder("CREATE_VERSION", "SELECT_OFFER");
            assertThat(statusByInsurer(newVersion)).containsExactly(Map.entry(ALPHA, "QUOTED"),
                    Map.entry(BETA, "DECLINED"));
            Map<String, JsonNode> newOffers = offersByInsurer(second);
            assertThat(newOffers).containsOnlyKeys(ALPHA);
            assertThat(newOffers.get(ALPHA).path("selectable").asBoolean()).isTrue();

            Instant replacedAt = Instant.parse(string(versionItem(first.quoteId(), 2), "createdAt"));
            for (int version : List.of(1, 2)) {
                assertThat(quoteItems(first.quoteId(), "V#%04d#LATE#".formatted(version))).isEmpty();
                for (Map<String, AttributeValue> result : quoteItems(first.quoteId(), "V#%04d#RESULT#".formatted(version))) {
                    Map<String, AttributeValue> query = providerQuery(string(result, "queryRef"));
                    assertThat(number(query, "version")).as("the query of %s", string(result, "sk")).isEqualTo(version);
                    if (version == 1) {
                        assertThat(Instant.parse(string(result, "updatedAt"))).as("arrived after the new version")
                                .isAfter(replacedAt);
                    }
                }
            }
            assertThat(providerQueriesOf(first.quoteId(), "QUOTE")).as("two insurer queries per version").hasSize(4);
            List<ExecutionListItem> executions = await().atMost(Duration.ofSeconds(stage.stepsMarginSeconds()))
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> executionsOf(first.quoteId()), found -> found.size() == 2
                            && found.stream().noneMatch(execution -> execution.status() == ExecutionStatus.RUNNING));
            assertThat(executions).as("the old execution was not cancelled")
                    .allSatisfy(execution -> assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED));
        } finally {
            forgetEnrichmentReuse(SEDAN, DRIVER);
            forgetEnrichmentReuse(BETA_DECLINES, DRIVER_ANA);
        }
    }

    /**
     * T20: a execução é interrompida ({@code StopExecution}) com a oferta da alpha gravada e a beta em processamento.
     * O {@code reconciler} da quote encontra a execução {@code ABORTED}, finaliza a versão com os resultados
     * existentes, mantém a oferta e grava a ocorrência {@code WORKFLOW_FAILED}.
     */
    @Test
    void t20_abortedExecutionIsFinalizedByTheReconcilerKeepingTheOffers() throws Exception {
        // NOTE: no Floci, o StopExecution marca ABORTED, mas o interpretador segue os estados sem registrá-los no
        // histórico: a espera da beta acaba e o ConsolidateQuote consolida cerca de 21 s depois do dispatch. O
        // reconciler só finaliza se um ciclo dele cair entre o StopExecution e essa consolidação, então o caso põe o
        // dispatch T20_DISPATCH_AFTER_CYCLE depois de um ciclo (o seguinte vem 60 ou 70 s depois dele) e repete com
        // outra cotação quando a continuação do Floci consolida antes
        forgetEnrichmentReuse(BETA_LATE, DRIVER_ANA);
        try {
            Duration dispatchLatency = Duration.ofSeconds(5);
            for (int attempt = 1; ; attempt++) {
                // NOTE: cada tentativa é um MISS, no mesmo regime da latência medida na anterior
                forgetEnrichmentReuse(BETA_LATE, DRIVER_ANA);
                Instant cycle = awaitReconcilerCycle();
                Instant postAt = cycle.plus(T20_DISPATCH_AFTER_CYCLE).minus(dispatchLatency);
                await().atMost(RECONCILER_BUDGET).pollInterval(Duration.ofMillis(250))
                        .until(() -> !Instant.now().isBefore(postAt));
                Instant posted = Instant.now();
                Accepted accepted = create(newKey(),
                        JSON.writeValueAsString(request(BETA_LATE, "PERSONAL", DRIVER_ANA)));
                String quoteId = accepted.quoteId();
                Map<String, AttributeValue> betaWaiter = await().atMost(stage.withInsurers(2))
                        .pollInterval(Duration.ofMillis(250))
                        .until(() -> item(QUOTES_TABLE, metaKey("WAITER#" + workRef(quoteId, 1, "INSURER", BETA))),
                                found -> !found.isEmpty());
                Instant dispatched = Instant.parse(string(betaWaiter, "createdAt"));
                dispatchLatency = Duration.between(posted, dispatched);
                List<ExecutionListItem> running = executionsOf(quoteId);
                assertThat(running).hasSize(1);
                String executionArn = running.getFirst().executionArn();
                await().atMost(stage.withInsurers(2)).pollInterval(Duration.ofMillis(250))
                        .until(() -> stringOrNull(single(quoteItems(quoteId, "V#0001#RESULT#" + ALPHA)), "status"),
                                "QUOTED"::equals);
                String phaseWhenStopped = string(versionItem(quoteId, 1), "phase");
                Map<String, AttributeValue> betaWhenStopped = single(quoteItems(quoteId, "V#0001#RESULT#" + BETA));
                List<Map<String, AttributeValue>> offersWhenStopped = quoteItems(quoteId, "V#0001#OFFER#");
                SFN.stopExecution(builder -> builder.executionArn(executionArn).error("E2E.FatalFailure")
                        .cause("T20: falha fatal provocada pelo e2e"));
                Instant stopped = Instant.now();

                JsonNode version = awaitTerminal(accepted, RECONCILER_BUDGET);

                String betaReason = insurerResult(version, BETA).path("reasonCode").asText();
                int tried = attempt;
                LOG.info(() -> "T20 attempt=%d quoteId=%s dispatch=cycle+%d ms stop=cycle+%d ms beta=%s".formatted(
                        tried, quoteId, Duration.between(cycle, dispatched).toMillis(),
                        Duration.between(cycle, stopped).toMillis(), betaReason));
                if (!"WORKFLOW_ABORTED".equals(betaReason)) {
                    // INVARIANT: a consolidação que ganhou a corrida mantém as ofertas e é a única transição a
                    // TERMINAL; sem a finalização do reconciler, não há WORKFLOW_FAILED
                    assertThat(quoteItems(quoteId, "V#0001#OFFER#")).as("the Floci continuation keeps the offers")
                            .containsExactlyInAnyOrderElementsOf(offersWhenStopped);
                    assertThat(quoteIssue("WORKFLOW_FAILED", quoteId + "|1")).as("consolidated before the reconciler")
                            .isEmpty();
                    assertThat(JSON.readTree(string(outbox(QUOTES_TABLE, uuidV5(quoteId + "|1|COMPLETED")), "detail"))
                            .path("outcome").asText()).isEqualTo(version.path("outcome").asText());
                    assertThat(attempt).as("the Floci continuation of the stopped execution consolidated it (%s) "
                            + "before a reconciler cycle in every attempt", betaReason).isLessThan(T20_ATTEMPTS);
                    continue;
                }
                assertThat(phaseWhenStopped).isEqualTo("QUOTING");
                assertThat(string(betaWhenStopped, "status")).isEqualTo("PROCESSING");
                assertThat(offersWhenStopped).hasSize(1);
                assertThat(SFN.describeExecution(builder -> builder.executionArn(executionArn)).status())
                        .isEqualTo(ExecutionStatus.ABORTED);
                assertThat(version.path("outcome").asText()).isEqualTo("PARTIAL");
                assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "QUOTED"),
                        Map.entry(BETA, "TIMED_OUT"));
                assertThat(quoteItems(quoteId, "V#0001#OFFER#")).as("the finalization keeps the offers")
                        .containsExactlyInAnyOrderElementsOf(offersWhenStopped);
                assertThat(offersByInsurer(accepted)).containsOnlyKeys(ALPHA);
                assertThat(versionItem(quoteId, 1)).as("out of the ACTIVE_VERSION index").doesNotContainKey("gsi2pk");
                Map<String, AttributeValue> issue = quoteIssue("WORKFLOW_FAILED", quoteId + "|1").orElseThrow();
                assertThat(string(issue, "type")).as("the item type (ADR 0015)").isEqualTo("ISSUE");
                assertThat(string(issue, "issueType")).isEqualTo("WORKFLOW_FAILED");
                assertThat(string(issue, "status")).isEqualTo("OPEN");
                assertThat(string(issue, "source")).isEqualTo("quote");
                assertThat(string(issue, "organizationId")).isEqualTo(ORGANIZATION);
                assertThat(string(issue, "quoteId")).isEqualTo(quoteId);
                assertThat(number(issue, "version")).isEqualTo(1);
                JsonNode completed = JSON.readTree(string(outbox(QUOTES_TABLE, uuidV5(quoteId + "|1|COMPLETED")),
                        "detail"));
                assertThat(completed.path("outcome").asText()).isEqualTo("PARTIAL");
                assertThat(completed.path("offerCount").asInt()).isEqualTo(1);
                return;
            }
        } finally {
            forgetEnrichmentReuse(BETA_LATE, DRIVER_ANA);
        }
    }

    /**
     * T31 nas DLQs implantadas: entregas de uma cotação concluída, postas na DLQ como se tivessem esgotado o
     * {@code maxReceiveCount}, são reenviadas como a tentativa 2, sem duplicar resultado, oferta ou chamada
     * (ADR 0007). O resultado volta à retomada mesmo com a versão fora do prazo; o pedido com chamada externa
     * fora do prazo é descartado com {@code DEAD_LETTER}.
     */
    @Test
    void t31_deadLettersAreReprocessedWithoutDuplicatingResultsOrCalls() throws Exception {
        // NOTE: a passagem pela RedrivePolicy com as visibilidades do stage (90 s × 5 na results, 150 s × 3 na
        // provider) passa de 7 min por mensagem; ela está no QuoteFunctionsFlociIT e no DeadLetterReprocessingIT,
        // com visibilidade de 1 s, e aqui o harness confere a RedrivePolicy implantada
        assertRedrivePolicy(RESULTS_QUEUE, RESULTS_DLQ, StageParameters.number(QUOTE_SERVERLESS, STAGE,
                "resultsMaxReceiveCount"));
        int requestsMaxReceiveCount = StageParameters.number(PROVIDER_SERVERLESS, STAGE, "requestQueueMaxReceiveCount");
        assertRedrivePolicy(INSURER_REQUESTS_QUEUE, INSURER_REQUESTS_QUEUE + "-dlq", requestsMaxReceiveCount);
        assertRedrivePolicy(RECONCILE_REQUESTS_QUEUE, RECONCILE_REQUESTS_QUEUE + "-dlq", requestsMaxReceiveCount);
        forgetEnrichmentReuse(VAN, DRIVER_ANA);
        try {
            Accepted accepted = create(newKey(), JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER_ANA)));
            JsonNode version = awaitTerminal(accepted, stage.withInsurers(1));
            Map<String, AttributeValue> query = insurerQuery(accepted.quoteId(), ALPHA);
            String queryId = string(query, "queryId");
            // NOTE: com a versão fora do prazo, o resultado da results-dlq volta à retomada mesmo assim (§9)
            DYNAMO_DB.updateItem(builder -> builder.tableName(QUOTES_TABLE)
                    .key(Map.of("pk", AttributeValue.fromS("QUOTE#" + accepted.quoteId()),
                            "sk", AttributeValue.fromS("V#0001")))
                    .updateExpression("SET deadlineAt = :past")
                    .expressionAttributeValues(Map.of(":past",
                            AttributeValue.fromS(Instant.now().minusSeconds(1).toString()))));
            List<Map<String, AttributeValue>> versionBefore = quoteItems(accepted.quoteId(), "V#0001");
            List<Map<String, AttributeValue>> queryBefore = providerHistory(queryId);
            String resultEventId = string(waiterOf(accepted.quoteId(), ALPHA), "resultEventId");
            Map<String, AttributeValue> request = outbox(QUOTES_TABLE, string(query, "requestEventId"));
            Instant requestDeadline =
                    Instant.parse(JSON.readTree(string(request, "detail")).path("deadlineAt").asText());
            String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
            Instant since = Instant.now();

            sendToDeadLetterQueue(RESULTS_DLQ, envelope("mmda.provider", outbox(QUERIES_TABLE, resultEventId)));
            sendToDeadLetterQueue(RECONCILE_REQUESTS_QUEUE + "-dlq", reconcileRequested(operationId, queryId));
            await().atMost(stage.withInsurers(1)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> Instant.now().isAfter(requestDeadline));
            sendToDeadLetterQueue(INSURER_REQUESTS_QUEUE + "-dlq", envelope("mmda.quote", request));

            awaitLog(QUOTE_RECONCILER_LOG, "dead letter " + resultEventId + " (InsurerResultRecorded) redriven as attempt 2",
                    since, RECONCILER_BUDGET);
            awaitLog(RESUME_LOG, "result " + resultEventId + " already consumed, discarded", since, RECONCILER_BUDGET);
            awaitLog(PROVIDER_RECONCILER_LOG, "queue=reconcile-requests attempt=2 target=" + operationId, since,
                    RECONCILER_BUDGET);
            Map<String, AttributeValue> operation = await().atMost(RECONCILER_BUDGET).pollInterval(Duration.ofSeconds(5))
                    .until(() -> item(QUERIES_TABLE, metaKey("OPERATION#" + operationId)),
                            found -> found.containsKey("status") && !"RUNNING".equals(string(found, "status")));
            String deadLetterTarget = "insurer-requests|" + queryId;
            Map<String, AttributeValue> deadLetter = await().atMost(RECONCILER_BUDGET).pollInterval(Duration.ofSeconds(5))
                    .until(() -> providerIssue("DEAD_LETTER", deadLetterTarget), Optional::isPresent)
                    .orElseThrow();

            assertThat(version.path("outcome").asText()).isEqualTo("COMPLETED");
            assertThat(quoteItems(accepted.quoteId(), "V#0001")).as("no result, offer or late evidence duplicated")
                    .containsExactlyInAnyOrderElementsOf(versionBefore);
            assertThat(queryByPrefix(QUOTES_TABLE, "CONSUMED#" + resultEventId, "META")).hasSize(1);
            assertThat(providerHistory(queryId)).as("no call, attempt or evidence repeated")
                    .containsExactlyInAnyOrderElementsOf(queryBefore);
            assertThat(string(operation, "status")).isEqualTo("SUCCEEDED");
            JsonNode reconciled = JSON.readTree(string(outbox(QUERIES_TABLE, uuidV5(operationId + "|2")), "detail"));
            assertThat(reconciled.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(reconciled.path("queryId").asText()).isEqualTo(queryId);
            assertThat(reconciled.path("organizationId").asText()).isEqualTo(ORGANIZATION);
            assertThat(reconciled.path("revision").asInt()).isEqualTo(2);
            assertThat(string(deadLetter, "issueType")).isEqualTo("DEAD_LETTER");
            assertThat(string(deadLetter, "status")).isEqualTo("OPEN");
            assertThat(string(deadLetter, "queryId")).isEqualTo(queryId);
            assertThat(string(deadLetter, "quoteId")).isEqualTo(accepted.quoteId());
            Map<String, AttributeValue> raised =
                    outbox(QUERIES_TABLE, uuidV5(string(deadLetter, "issueId") + "|" + number(deadLetter, "revision")));
            assertThat(string(raised, "detailType")).isIn("OperationIssueRaised", "OperationIssueUpdated");
        } finally {
            forgetEnrichmentReuse(VAN, DRIVER_ANA);
        }
    }

    /**
     * T18: a oferta expirada e a oferta de outra organização são recusadas sem gravar intenção de proposta: nem
     * {@code SELECTION}, nem {@code selectionId} no {@code META}, nem idempotência, nem {@code OfferSelected}. A
     * expiração é fabricada no {@code validUntil} da oferta e desfeita no {@code finally}.
     */
    @Test
    void t18_expiredOfferOrOfferOfAnotherOrganizationIsRejectedWithoutRecordingAnIntent() throws Exception {
        forgetEnrichmentReuse(ALPHA_DECLINES, DRIVER_ANA);
        Map<String, AttributeValue> offerKey = null;
        String originalValidUntil = null;
        try {
            Accepted accepted = create(newKey(),
                    JSON.writeValueAsString(request(ALPHA_DECLINES, "PERSONAL", DRIVER_ANA)));
            JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));
            JsonNode offer = offersByInsurer(accepted).get(BETA);
            String offerId = offer.path("id").asText();
            String globexKey = newKey();

            HttpResponse<String> ofAnotherOrganization = select(GLOBEX, accepted, globexKey, offerId);
            HttpResponse<String> readByAnotherOrganization = call(GLOBEX, "GET", accepted.statusUrl(), null, null);
            offerKey = Map.of("pk", AttributeValue.fromS("QUOTE#" + accepted.quoteId()),
                    "sk", AttributeValue.fromS("V#0001#OFFER#" + offerId));
            originalValidUntil = string(item(QUOTES_TABLE, offerKey), "validUntil");
            setValidUntil(offerKey, Instant.now().minusSeconds(1).toString());
            JsonNode expiredOffers = getOk(accepted.statusUrl() + "/offers");
            JsonNode versionAfterExpiry = getOk(accepted.statusUrl());
            String writerKey = newKey();
            HttpResponse<String> expired = select(WRITER, accepted, writerKey, offerId);

            assertThat(statusByInsurer(version)).containsExactly(Map.entry(ALPHA, "DECLINED"), Map.entry(BETA, "QUOTED"));
            assertThat(offer.path("selectable").asBoolean()).as("valid when the other organization tried").isTrue();
            assertError(ofAnotherOrganization, 404, "NOT_FOUND");
            assertError(readByAnotherOrganization, 404, "NOT_FOUND");
            assertError(expired, 409, "OFFER_EXPIRED");
            assertThat(expiredOffers.path("items")).singleElement().satisfies(item -> {
                assertThat(item.path("selectable").asBoolean()).isFalse();
                assertThat(item.path("unavailabilityReason").asText()).isEqualTo("OFFER_EXPIRED");
            });
            assertThat(texts(versionAfterExpiry.path("allowedActions"))).containsExactly("CREATE_VERSION");
            assertNoSelectionRecorded(accepted.quoteId(),
                    idempotencyPk(GLOBEX_ORGANIZATION, "SELECT_OFFER", accepted.quoteId(), globexKey),
                    idempotencyPk(ORGANIZATION, "SELECT_OFFER", accepted.quoteId(), writerKey));
        } finally {
            if (originalValidUntil != null) {
                setValidUntil(offerKey, originalValidUntil);
            }
            forgetEnrichmentReuse(ALPHA_DECLINES, DRIVER_ANA);
        }
    }

    /**
     * T22 na API implantada: cada operação do contrato é exercitada e conferida contra o {@code openapi.yaml}, com
     * erros, {@code PARTIAL} com pendência e {@code NEEDS_INFORMATION} do enriquecimento. O catálogo servido, os
     * erros e a pendência da beta são os das fixtures, e as coberturas das ofertas são as do {@code catalog.json}.
     */
    @Test
    void t22_everyContractOperationAnswersTheSharedSchemaWithErrorsAndPartialResults() throws Exception {
        forgetEnrichmentReuse(BETA_NEEDS_INFO, DRIVER_ANA);
        forgetEnrichmentReuse(UNKNOWN_TO_THE_PROVIDER, DRIVER_NOT_FOUND);
        try {
            Accepted partial = create(newKey(),
                    JSON.writeValueAsString(request(BETA_NEEDS_INFO, "PERSONAL", DRIVER_ANA)));
            Accepted notFound = create(newKey(),
                    JSON.writeValueAsString(request(UNKNOWN_TO_THE_PROVIDER, "PERSONAL", DRIVER_NOT_FOUND)));
            for (User user : USERS) {
                JsonNode me = okJson(user, "/v1/me");
                assertThat(me.path("organizationId").asText()).as(user.username())
                        .isEqualTo(user.groups().getFirst().substring("org_".length()));
                assertThat(texts(me.path("permissions"))).as(user.username())
                        .containsExactlyInAnyOrderElementsOf(user.permissions());
            }
            for (JsonNode exchange : fixture("catalog-pages.json").path("exchanges")) {
                assertThat(okJson(OPERATOR, exchange.at("/request/path").asText()))
                        .as(exchange.path("name").asText()).isEqualTo(exchange.at("/response/body"));
            }
            JsonNode errors = fixture("errors.json").path("exchanges");
            JsonNode validation = errors.get(0);
            HttpResponse<String> invalid = call(WRITER, "POST", "/v1/quotes", newKey(),
                    JSON.writeValueAsString(validation.at("/request/body")));
            HttpResponse<String> unauthenticated = api.send(null, "GET", "/v1/me", Map.of(), null);
            HttpResponse<String> tampered = api.send(TOKENS.get(WRITER) + "x", "GET", "/v1/me", Map.of(), null);
            HttpResponse<String> unsupported = api.send(TOKENS.get(WRITER), "POST", "/v1/quotes",
                    Map.of("Content-Type", "text/plain", "Idempotency-Key", newKey()),
                    JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER)));
            HttpResponse<String> withoutOperationsRead = call(WRITER, "GET", "/v1/operations/issues", null, null);
            HttpResponse<String> unknownQuote = call(OPERATOR, "GET",
                    "/v1/quotes/" + UUID.randomUUID() + "/versions/1", null, null);
            HttpResponse<String> unknownOperation = call(OPERATOR, "GET",
                    "/v1/operations/op_" + UUID.randomUUID().toString().replace("-", ""), null, null);
            HttpResponse<String> unknownQuery = reconcile(OPERATOR,
                    "q_" + UUID.randomUUID().toString().replace("-", ""), newKey(), "e2e: consulta desconhecida");
            HttpResponse<String> issues = call(OPERATOR, "GET", "/v1/operations/issues?limit=5", null, null);
            HttpResponse<String> quotes = call(WRITER, "GET", "/v1/quotes?limit=2", null, null);

            JsonNode partialVersion = awaitTerminal(partial, stage.withInsurers(2));
            JsonNode partialOffers = getOk(partial.statusUrl() + "/offers?limit=1");
            JsonNode notFoundVersion = awaitTerminal(notFound, stage.withInsurers(1));
            HttpResponse<String> staleVersion = postVersion(partial.quoteId(), newKey(),
                    JSON.writeValueAsString(versionRequest(2, BETA_NEEDS_INFO, "PERSONAL", DRIVER_ANA)));
            HttpResponse<String> readByAnotherOrganization = call(GLOBEX, "GET", partial.statusUrl(), null, null);
            String alphaOffer = partialOffers.path("items").get(0).path("id").asText();
            HttpResponse<String> selected = select(OPERATOR, partial, newKey(), alphaOffer);
            JsonNode selectedVersion = getOk(partial.statusUrl());

            JsonNode expectedPartial = fixture("quote-partial-needs-information.json").at("/exchanges/0/response/body");
            assertThat(partialVersion.path("outcome").asText()).isEqualTo("PARTIAL");
            assertThat(partialVersion.path("outcomeReason").isNull()).isTrue();
            assertThat(partialVersion.path("missingFields")).as("the pending fields stay in the insurer result")
                    .isEmpty();
            assertThat(statusByInsurer(partialVersion)).isEqualTo(statusByInsurer(expectedPartial));
            JsonNode beta = insurerResult(partialVersion, BETA);
            JsonNode expectedBeta = insurerResult(expectedPartial, BETA);
            assertThat(beta.path("reasonCode")).isEqualTo(expectedBeta.path("reasonCode"));
            assertThat(beta.path("missingFields")).isEqualTo(expectedBeta.path("missingFields"));
            assertThat(texts(partialVersion.path("allowedActions")))
                    .containsExactlyElementsOf(texts(expectedPartial.path("allowedActions")));
            JsonNode alpha = partialOffers.path("items").get(0);
            assertThat(partialOffers.path("items")).hasSize(1);
            assertThat(partialOffers.path("nextCursor").isNull()).isTrue();
            assertThat(alpha.path("insurerId").asText()).isEqualTo(ALPHA);
            assertThat(alpha.path("coverages")).as("the coverage profile of catalog.json, read by the simulators")
                    .isEqualTo(coverageProfile("COMPREHENSIVE").path("coverages"));
            assertThat(alpha.path("deductibles")).singleElement()
                    .satisfies(deductible -> assertThat(deductible.path("coverageCode").asText()).isEqualTo("COLLISION"));

            JsonNode expectedVehicleNotFound = fixture("quote-needs-information.json")
                    .at("/exchanges/1/response/body/missingFields/0");
            assertThat(notFoundVersion.path("outcome").asText()).isEqualTo("NEEDS_INFORMATION");
            assertThat(notFoundVersion.path("outcomeReason").asText()).isEqualTo("ENRICHMENT_NOT_FOUND");
            assertThat(notFoundVersion.path("missingFields")).hasSize(2);
            assertThat(notFoundVersion.path("missingFields").get(0)).isEqualTo(expectedVehicleNotFound);
            JsonNode driverNotFound = notFoundVersion.path("missingFields").get(1);
            assertThat(driverNotFound.path("path").asText()).isEqualTo("risk.principalDriverRef");
            assertThat(driverNotFound.path("reasonCode").asText()).isEqualTo("ENRICHMENT_NOT_FOUND");
            assertThat(notFoundVersion.path("insurerResults")).isEmpty();
            assertThat(texts(notFoundVersion.path("allowedActions"))).containsExactly("CREATE_VERSION");
            assertNoInsurerConsulted(notFound.quoteId());

            JsonNode invalidError = assertError(invalid, 400, "VALIDATION_ERROR");
            assertThat(invalidError.path("message")).isEqualTo(validation.at("/response/body/message"));
            assertThat(invalidError.path("fieldErrors")).isEqualTo(validation.at("/response/body/fieldErrors"));
            for (HttpResponse<String> response : List.of(unauthenticated, tampered)) {
                assertThat(assertError(response, 401, "UNAUTHENTICATED").path("message"))
                        .isEqualTo(errors.get(1).at("/response/body/message"));
            }
            assertError(unsupported, 415, "UNSUPPORTED_MEDIA_TYPE");
            assertError(withoutOperationsRead, 403, "FORBIDDEN");
            for (HttpResponse<String> response : List.of(unknownQuote, unknownOperation, unknownQuery,
                    readByAnotherOrganization)) {
                assertError(response, 404, "NOT_FOUND");
            }
            assertError(staleVersion, 409, "VERSION_CONFLICT");
            assertThat(issues.statusCode()).as(issues.body()).isEqualTo(200);
            assertThat(quotes.statusCode()).as(quotes.body()).isEqualTo(200);
            assertThat(selected.statusCode()).as(selected.body()).isEqualTo(201);
            assertThat(selectedVersion.path("selection")).isEqualTo(JSON.readTree(selected.body()));
            assertThat(selectedVersion.path("allowedActions")).isEmpty();
            assertThat(api.exercised()).as("operations of contracts/openapi.yaml exercised by the e2e")
                    .containsAll(api.operationIds());
            // NOTE: aqui a nova versão só recebe 409 e a reconciliação, 404; o 2xx delas é conferido contra o contrato
            // no T17 e no T29 (nova versão) e no m5_manual (reconciliação aceita e acompanhada)
            Set<String> successElsewhere = Set.of("createQuoteVersion", "reconcileQuery", "getOperation");
            assertThat(api.succeeded()).as("operations whose 2xx response T22 itself checks against the contract")
                    .containsAll(api.operationIds().stream().filter(id -> !successElsewhere.contains(id)).toList());
        } finally {
            forgetEnrichmentReuse(BETA_NEEDS_INFO, DRIVER_ANA);
            forgetEnrichmentReuse(UNKNOWN_TO_THE_PROVIDER, DRIVER_NOT_FOUND);
        }
    }

    /**
     * T28: a mesma oferta devolve a seleção existente, com a mesma chave ou com outra; outra oferta recebe 409
     * {@code SELECTION_ALREADY_EXISTS}, e a chave reutilizada com outra oferta, 409 {@code IDEMPOTENCY_CONFLICT}. Fica
     * uma única {@code SELECTION} e um único {@code OfferSelected}.
     */
    @Test
    void t28_repeatedSelectionReturnsTheExistingOneAndAnotherOfferConflicts() throws Exception {
        forgetEnrichmentReuse(BETA_DUPLICATE, DRIVER_ANA);
        try {
            Accepted accepted = create(newKey(),
                    JSON.writeValueAsString(request(BETA_DUPLICATE, "PERSONAL", DRIVER_ANA)));
            awaitTerminal(accepted, stage.withInsurers(2));
            Map<String, JsonNode> offers = offersByInsurer(accepted);
            String alphaOffer = offers.get(ALPHA).path("id").asText();
            String betaOffer = offers.get(BETA).path("id").asText();
            String key = newKey();

            HttpResponse<String> first = select(WRITER, accepted, key, alphaOffer);
            HttpResponse<String> replayed = select(WRITER, accepted, key, alphaOffer);
            HttpResponse<String> sameOfferOtherKey = select(WRITER, accepted, newKey(), alphaOffer);
            HttpResponse<String> otherOffer = select(WRITER, accepted, newKey(), betaOffer);
            HttpResponse<String> reusedKey = select(WRITER, accepted, key, betaOffer);
            JsonNode version = getOk(accepted.statusUrl());
            JsonNode offersAfter = getOk(accepted.statusUrl() + "/offers");

            assertThat(offers).containsOnlyKeys(ALPHA, BETA);
            assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
            JsonNode selection = JSON.readTree(first.body());
            assertThat(selection.path("offerId").asText()).isEqualTo(alphaOffer);
            assertThat(selection.path("status").asText()).isEqualTo("INTENT_RECORDED");
            assertThat(selection.path("selectionId").asText()).startsWith("sel_");
            for (HttpResponse<String> same : List.of(replayed, sameOfferOtherKey)) {
                assertThat(same.statusCode()).as(same.body()).isEqualTo(201);
                assertThat(JSON.readTree(same.body())).isEqualTo(selection);
            }
            assertError(otherOffer, 409, "SELECTION_ALREADY_EXISTS");
            assertError(reusedKey, 409, "IDEMPOTENCY_CONFLICT");
            assertThat(version.path("selection")).isEqualTo(selection);
            assertThat(version.path("allowedActions")).isEmpty();
            assertThat(offersAfter.path("items")).hasSize(2).allSatisfy(item -> {
                assertThat(item.path("selectable").asBoolean()).isFalse();
                assertThat(item.path("unavailabilityReason").asText()).isEqualTo("SELECTION_ALREADY_EXISTS");
            });
            assertThat(quoteItems(accepted.quoteId(), "V#0001#SELECTION")).singleElement()
                    .satisfies(item -> assertThat(string(item, "offerId")).isEqualTo(alphaOffer));
            assertThat(outboxOf("OfferSelected", "quoteId", accepted.quoteId())).singleElement()
                    .satisfies(outbox -> assertThat(string(outbox, "detail"))
                            .contains(selection.path("selectionId").asText()));
        } finally {
            forgetEnrichmentReuse(BETA_DUPLICATE, DRIVER_ANA);
        }
    }

    /**
     * T29: seleções e novas versões disputam a mesma versão atual, e só um dos lados vence. Se a nova versão vence, a
     * disputa se repete na versão criada. Depois da seleção, a nova versão recebe 409 {@code QUOTE_ALREADY_SELECTED},
     * e a pendência da beta ({@code NEEDS_INFORMATION}), lida depois da seleção, não oferece {@code CREATE_VERSION}.
     */
    @Test
    void t29_selectionAndNewVersionRaceHasOneWinnerAndASelectedQuoteTakesNoNewVersion() throws Exception {
        forgetEnrichmentReuse(BETA_NEEDS_INFO, DRIVER);
        ExecutorService executor = Executors.newFixedThreadPool(2 * T29_CONTENDERS);
        try {
            Accepted current = create(newKey(), JSON.writeValueAsString(request(BETA_NEEDS_INFO, "PERSONAL", DRIVER)));
            JsonNode selection = null;
            for (int round = 1; selection == null && round <= T29_ROUNDS; round++) {
                String offerId = awaitFirstOffer(current, stage.withInsurers(2)).path("items").get(0).path("id")
                        .asText();
                String phase = getOk(current.statusUrl()).path("phase").asText();
                Race race = race(executor, current, offerId);
                List<HttpResponse<String>> selected = race.selections().stream()
                        .filter(response -> response.statusCode() == 201).toList();
                List<HttpResponse<String>> created = race.versions().stream()
                        .filter(response -> response.statusCode() == 202).toList();
                String summary = "T29 round=%d quoteId=%s version=%d phase=%s selections=%s versions=%s".formatted(round,
                        current.quoteId(), current.version(), phase, statuses(race.selections()),
                        statuses(race.versions()));
                LOG.info(summary);

                assertThat(selected.isEmpty()).as("only one side wins the race").isNotEqualTo(created.isEmpty());
                if (!selected.isEmpty()) {
                    assertThat(race.selections()).allSatisfy(response ->
                            assertThat(response.statusCode()).as(response.body()).isEqualTo(201));
                    assertThat(race.selections().stream().map(response -> selectionIdOf(response)).distinct())
                            .hasSize(1);
                    assertThat(race.versions()).allSatisfy(response ->
                            assertError(response, 409, "QUOTE_ALREADY_SELECTED"));
                    selection = JSON.readTree(selected.getFirst().body());
                } else {
                    assertThat(created).hasSize(1);
                    assertThat(race.selections()).allSatisfy(response ->
                            assertError(response, 409, "VERSION_SUPERSEDED"));
                    assertThat(race.versions().stream().filter(response -> response.statusCode() != 202))
                            .allSatisfy(response -> assertError(response, 409, "VERSION_CONFLICT"));
                    current = accepted(created.getFirst());
                }
            }
            if (selection == null) {
                String offerId = awaitFirstOffer(current, stage.withInsurers(2)).path("items").get(0).path("id")
                        .asText();
                HttpResponse<String> selected = select(WRITER, current, newKey(), offerId);
                assertThat(selected.statusCode()).as(selected.body()).isEqualTo(201);
                selection = JSON.readTree(selected.body());
            }
            Accepted selectedVersion = current;

            HttpResponse<String> blocked = postVersion(selectedVersion.quoteId(), newKey(), JSON.writeValueAsString(
                    versionRequest(selectedVersion.version(), BETA_NEEDS_INFO, "PERSONAL", DRIVER)));
            JsonNode terminal = awaitTerminal(selectedVersion, stage.withInsurers(2));

            assertError(blocked, 409, "QUOTE_ALREADY_SELECTED");
            assertThat(terminal.path("outcome").asText()).isEqualTo("PARTIAL");
            JsonNode beta = insurerResult(terminal, BETA);
            assertThat(beta.path("status").asText()).isEqualTo("NEEDS_INFORMATION");
            assertThat(beta.path("missingFields")).isNotEmpty();
            assertThat(terminal.path("selection")).isEqualTo(selection);
            assertThat(terminal.path("allowedActions")).as("no CREATE_VERSION after the selection").isEmpty();
            Map<String, AttributeValue> meta = item(QUOTES_TABLE, metaKey("QUOTE#" + selectedVersion.quoteId()));
            assertThat(number(meta, "currentVersion")).isEqualTo(selectedVersion.version());
            assertThat(string(meta, "selectionId")).isEqualTo(selection.path("selectionId").asText());
            assertThat(quoteItems(selectedVersion.quoteId(), "V#")).filteredOn(item -> string(item, "sk")
                    .endsWith("#SELECTION")).singleElement()
                    .satisfies(item -> assertThat(number(item, "version")).isEqualTo(selectedVersion.version()));
            Instant selectedAt = Instant.parse(selection.path("selectedAt").asText());
            Instant betaAt = Instant.parse(beta.path("updatedAt").asText());
            LOG.info(() -> "T29 quoteId=%s version=%d selectedAt=%s betaNeedsInformationAt=%s (%s the selection)"
                    .formatted(selectedVersion.quoteId(), selectedVersion.version(), selectedAt, betaAt,
                            betaAt.isAfter(selectedAt) ? "after" : "before"));
            assertThat(betaAt).as("the NEEDS_INFORMATION is recorded after the selection, the case of PRD T29")
                    .isAfter(selectedAt);
        } finally {
            executor.shutdownNow();
            forgetEnrichmentReuse(BETA_NEEDS_INFO, DRIVER);
        }
    }

    /**
     * T30: sem {@code offers:select}, a seleção de uma oferta válida recebe 403; sem {@code operations:reconcile},
     * a reconciliação de uma consulta da organização também. Nada é gravado, e as ações oferecidas seguem as
     * permissões do usuário. Sem {@code operations:read}, a listagem de ocorrências recebe 403.
     */
    @Test
    void t30_userWithoutPermissionGets403OnSelectionAndReconciliation() throws Exception {
        forgetEnrichmentReuse(ALPHA_DOWN, DRIVER_ANA);
        try {
            Accepted accepted = create(newKey(), JSON.writeValueAsString(request(ALPHA_DOWN, "PERSONAL", DRIVER_ANA)));
            JsonNode version = awaitTerminal(accepted, stage.withInsurers(2));
            JsonNode offer = offersByInsurer(accepted).get(BETA);
            String queryId = string(insurerQuery(accepted.quoteId(), BETA), "queryId");
            String selectionKey = newKey();
            String reconcileKey = newKey();

            HttpResponse<String> selection = select(VIEWER, accepted, selectionKey, offer.path("id").asText());
            HttpResponse<String> reconciliation = reconcile(VIEWER, queryId, reconcileKey, "e2e: T30 sem permissão");
            HttpResponse<String> writerReconciliation = reconcile(WRITER, queryId, newKey(), "e2e: T30 sem permissão");
            HttpResponse<String> writerIssues = call(WRITER, "GET", "/v1/operations/issues", null, null);
            JsonNode viewerVersion = okJson(VIEWER, accepted.statusUrl());

            assertThat(statusByInsurer(version)).containsEntry(ALPHA, "UNAVAILABLE").containsEntry(BETA, "QUOTED");
            assertThat(offer.path("selectable").asBoolean()).isTrue();
            assertThat(texts(version.path("allowedActions"))).containsExactlyInAnyOrder("CREATE_VERSION", "SELECT_OFFER");
            assertError(selection, 403, "FORBIDDEN");
            assertError(reconciliation, 403, "FORBIDDEN");
            assertError(writerReconciliation, 403, "FORBIDDEN");
            assertError(writerIssues, 403, "FORBIDDEN");
            assertThat(texts(viewerVersion.path("allowedActions"))).containsExactly("CREATE_VERSION");
            assertNoSelectionRecorded(accepted.quoteId(),
                    idempotencyPk(ORGANIZATION, "SELECT_OFFER", accepted.quoteId(), selectionKey));
            assertThat(item(QUOTES_TABLE, metaKey(idempotencyPk(ORGANIZATION, "RECONCILE", queryId, reconcileKey))))
                    .isEmpty();
            assertThat(operationsOfQuery(queryId)).as("no OPERATION# for the query").isEmpty();
        } finally {
            forgetEnrichmentReuse(ALPHA_DOWN, DRIVER_ANA);
        }
    }

    /**
     * Reconciliação manual ponta a ponta (§8 do desenho): uma {@code UNKNOWN_QUERY} publicada pelo e2e em nome da
     * provider torna conhecida uma consulta concluída. O {@code reconcile} vai à provider pelo
     * {@code QueryReconcileRequested} e volta pelo {@code QueryReconciled} e pelo {@code issue-projector} até o
     * {@code GET /v1/operations/{operationId}}; a listagem mostra só ocorrências da organização, sem dado bruto.
     */
    @Test
    void m5_manualReconciliationOfAConcludedQueryRoundTripsThroughTheProviderAndTheIssueProjector()
            throws Exception {
        forgetEnrichmentReuse(FLAKY, DRIVER_ANA);
        String issueId = "is_" + UUID.randomUUID().toString().replace("-", "");
        String issueEventId = uuidV5(issueId + "|1");
        String queryId = null;
        try {
            Accepted quote = create(newKey(), JSON.writeValueAsString(request(FLAKY, "PERSONAL", DRIVER_ANA)));
            awaitTerminal(quote, stage.withInsurers(2));
            queryId = string(insurerQuery(quote.quoteId(), ALPHA), "queryId");
            String knownQuery = queryId;
            publishProviderIssue(issueEventId, issueId, knownQuery, quote.quoteId());
            await().atMost(RECONCILE_BUDGET).pollInterval(Duration.ofSeconds(1))
                    .until(() -> item(QUOTES_TABLE, metaKey("QUERYREF#" + knownQuery)), found -> !found.isEmpty());
            String openUnknown = "/v1/operations/issues?type=UNKNOWN_QUERY&status=OPEN&limit=10";
            JsonNode listed = issueOf(allItems(OPERATOR, openUnknown), issueId);
            JsonNode listedToViewer = issueOf(allItems(VIEWER, openUnknown), issueId);
            List<JsonNode> acmeIssues = itemsOf(okJson(OPERATOR, "/v1/operations/issues?limit=25"));
            List<JsonNode> globexIssues = allItems(GLOBEX, "/v1/operations/issues");
            String reason = "e2e: conferir na provider a consulta concluída";
            String key = newKey();

            HttpResponse<String> requested = reconcile(OPERATOR, knownQuery, key, reason);
            HttpResponse<String> replayed = reconcile(OPERATOR, knownQuery, key, reason);
            HttpResponse<String> reusedKey = reconcile(OPERATOR, knownQuery, key, "e2e: outro motivo");
            HttpResponse<String> ofAnotherOrganization = reconcile(GLOBEX, knownQuery, newKey(), reason);
            assertThat(requested.statusCode()).as(requested.body()).isEqualTo(202);
            JsonNode reconcileAccepted = JSON.readTree(requested.body());
            String operationId = reconcileAccepted.path("operationId").asText();
            String statusUrl = reconcileAccepted.path("statusUrl").asText();
            JsonNode first = okJson(OPERATOR, statusUrl);
            HttpResponse<String> readByAnotherOrganization = call(GLOBEX, "GET", statusUrl, null, null);
            JsonNode finished = await().atMost(RECONCILE_BUDGET).pollInterval(Duration.ofSeconds(1))
                    .until(() -> okJson(OPERATOR, statusUrl),
                            operation -> Set.of("SUCCEEDED", "FAILED").contains(operation.path("status").asText()));

            assertThat(listed.path("queryId").asText()).isEqualTo(knownQuery);
            assertThat(listed.path("quoteId").asText()).isEqualTo(quote.quoteId());
            assertThat(listed.path("status").asText()).isEqualTo("OPEN");
            assertThat(texts(listed.path("allowedActions"))).containsExactly("RECONCILE");
            assertThat(listedToViewer.path("allowedActions")).as("without operations:reconcile").isEmpty();
            assertThat(acmeIssues).isNotEmpty().allSatisfy(issue -> assertThat(string(
                    item(QUOTES_TABLE, metaKey("ISSUE#" + issue.path("id").asText())), "organizationId"))
                    .isEqualTo(ORGANIZATION));
            assertThat(globexIssues).noneMatch(issue -> issue.path("id").asText().equals(issueId)
                    || issue.path("quoteId").asText().equals(quote.quoteId()));
            assertThat(reconcileAccepted.path("status").asText()).isEqualTo("QUEUED");
            assertThat(statusUrl).isEqualTo("/v1/operations/" + operationId);
            assertThat(requested.headers().firstValue("Location")).contains(statusUrl);
            assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(202);
            assertThat(JSON.readTree(replayed.body()).path("operationId").asText()).isEqualTo(operationId);
            assertError(reusedKey, 409, "IDEMPOTENCY_CONFLICT");
            assertError(ofAnotherOrganization, 404, "NOT_FOUND");
            assertError(readByAnotherOrganization, 404, "NOT_FOUND");
            assertThat(first.path("queryId").asText()).isEqualTo(knownQuery);
            assertThat(finished.path("status").asText()).as("the query was already concluded").isEqualTo("SUCCEEDED");
            assertThat(finished.path("safeSummary").asText()).isNotBlank();
            assertThat(Instant.parse(finished.path("updatedAt").asText()))
                    .isAfterOrEqualTo(Instant.parse(first.path("updatedAt").asText()));
            Map<String, AttributeValue> operation = item(QUOTES_TABLE, metaKey("OPERATION#" + operationId));
            assertThat(string(operation, "reason")).isEqualTo(reason);
            assertThat(string(operation, "requestedBy")).isNotBlank();
            assertThat(number(operation, "revision")).isEqualTo(2);
            assertThat(outboxOf("QueryReconcileRequested", "operationId", operationId)).singleElement()
                    .satisfies(outbox -> assertThat(outbox).containsKey("publishedAt"));
            assertThat(string(item(QUERIES_TABLE, metaKey("OPERATION#" + operationId)), "status"))
                    .isEqualTo("SUCCEEDED");
            String reconciledEventId = uuidV5(operationId + "|2");
            assertThat(JSON.readTree(string(outbox(QUERIES_TABLE, reconciledEventId), "detail")).path("status")
                    .asText()).isEqualTo("SUCCEEDED");
            assertThat(item(QUOTES_TABLE, metaKey("CONSUMED#" + reconciledEventId))).as("projected by the quote")
                    .isNotEmpty();
        } finally {
            deleteQuoteItem("ISSUE#" + issueId);
            deleteQuoteItem("CONSUMED#" + issueEventId);
            if (queryId != null) {
                deleteQuoteItem("QUERYREF#" + queryId);
            }
            forgetEnrichmentReuse(FLAKY, DRIVER_ANA);
        }
    }

    /**
     * Catálogo e listagem (§11): cada rota do catálogo, paginada pelo cursor, devolve o {@code catalog.json} da
     * organização, com busca sem acento nem maiúscula; a listagem de cotações vai da mais nova para a mais antiga,
     * filtra por fase, resultado e janela de criação e não mistura organizações.
     */
    @Test
    void m5_catalogAndQuoteListingPageWithCursorAndFilters() throws Exception {
        JsonNode catalog = JSON.readTree(CATALOG.toFile());
        JsonNode acme = organizationOf(catalog, ORGANIZATION);
        for (String kind : List.of("customers", "vehicles", "drivers")) {
            assertThat(allItems(OPERATOR, "/v1/catalog/" + kind + "?limit=4")).as(kind)
                    .containsExactlyElementsOf(idsAndLabels(acme.path(kind)));
        }
        List<JsonNode> profiles = new ArrayList<>();
        catalog.path("coverageProfiles").forEach(profiles::add);
        assertThat(allItems(OPERATOR, "/v1/catalog/coverage-profiles?limit=2")).containsExactlyElementsOf(profiles);
        assertThat(ids(allItems(GLOBEX, "/v1/catalog/vehicles")))
                .containsExactlyElementsOf(ids(idsAndLabels(organizationOf(catalog, GLOBEX_ORGANIZATION)
                        .path("vehicles"))));
        assertThat(ids(allItems(OPERATOR, "/v1/catalog/vehicles?search=SEDA&limit=2")))
                .containsExactlyElementsOf(matching(acme.path("vehicles"), "Sedã"));
        assertThat(ids(allItems(OPERATOR, "/v1/catalog/drivers?search=davi"))).containsExactly(DRIVER_NOT_FOUND);
        assertError(call(OPERATOR, "GET", "/v1/catalog/vehicles?limit=0", null, null), 400, "VALIDATION_ERROR");

        Instant from = Instant.now();
        Accepted oldest = create(newKey(), JSON.writeValueAsString(request(SALVAGE, "PERSONAL", DRIVER)));
        Accepted middle = create(newKey(),
                JSON.writeValueAsString(request(VAN, "COMMERCIAL", DRIVER_WITHOUT_LICENSE_DATE)));
        Accepted newest = create(newKey(), JSON.writeValueAsString(request(SALVAGE, "PERSONAL", DRIVER)));
        Instant to = Instant.now();
        for (Accepted accepted : List.of(oldest, middle, newest)) {
            awaitTerminal(accepted, stage.withoutExternalWaits());
        }
        String window = "createdFrom=" + from + "&createdTo=" + to;
        List<String> created = List.of(newest.quoteId(), middle.quoteId(), oldest.quoteId());

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<JsonNode> listed = allItems(WRITER, "/v1/quotes?limit=1&" + window);
            assertThat(ids(listed)).containsExactlyElementsOf(created);
            assertThat(listed).allSatisfy(summary -> {
                assertThat(summary.path("phase").asText()).isEqualTo("TERMINAL");
                assertThat(summary.path("currentVersion").asInt()).isEqualTo(1);
                assertThat(summary.path("offerCount").asInt()).isZero();
                assertThat(Instant.parse(summary.path("createdAt").asText())).isBetween(from, to);
            });
        });
        assertThat(ids(allItems(WRITER, "/v1/quotes?limit=1&outcome=INELIGIBLE&" + window)))
                .containsExactly(newest.quoteId(), oldest.quoteId());
        assertThat(ids(allItems(WRITER, "/v1/quotes?outcome=NEEDS_INFORMATION&phase=TERMINAL&" + window)))
                .containsExactly(middle.quoteId());
        assertThat(allItems(WRITER, "/v1/quotes?phase=QUOTING&" + window)).isEmpty();
        assertThat(allItems(GLOBEX, "/v1/quotes?" + window)).as("other organization").isEmpty();
        String newestCreatedAt = itemsOf(okJson(WRITER, "/v1/quotes?limit=1&" + window)).getFirst()
                .path("createdAt").asText();
        assertThat(ids(allItems(WRITER, "/v1/quotes?createdFrom=" + newestCreatedAt + "&createdTo=" + newestCreatedAt)))
                .as("inclusive bounds").containsExactly(newest.quoteId());
        assertError(call(WRITER, "GET", "/v1/quotes?phase=DONE", null, null), 400, "VALIDATION_ERROR");
        assertError(call(WRITER, "GET", "/v1/quotes?createdFrom=" + to + "&createdTo=" + from, null, null), 400,
                "VALIDATION_ERROR");
        assertError(call(WRITER, "GET", "/v1/quotes?cursor=bm90LWEtY3Vyc29y", null, null), 400, "VALIDATION_ERROR");
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
        Map<String, AttributeValue> result = single(quoteItems(quoteId, "V#0001#RESULT#" + string(offer, "insurerId")));
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

    /** T25 e T27: a versão não pediu cotação a nenhuma seguradora, e a provider não abriu consulta de seguradora. */
    private static void assertNoInsurerConsulted(String quoteId) {
        assertThat(quoteItems(quoteId, "V#0001#RESULT#")).isEmpty();
        assertThat(DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUOTES_TABLE)
                        .filterExpression("begins_with(pk, :waiter) AND quoteId = :quoteId AND kind = :insurer")
                        .expressionAttributeValues(Map.of(":waiter", AttributeValue.fromS("WAITER#"),
                                ":quoteId", AttributeValue.fromS(quoteId), ":insurer", AttributeValue.fromS("INSURER"))))
                .items().stream().count()).as("insurer waiters").isZero();
        assertThat(providerQueriesOf(quoteId, "QUOTE")).as("insurer queries").isEmpty();
    }

    /**
     * Apaga o {@code REUSE#} e a partição {@code INFLIGHT#} ({@code META} e {@code FOLLOWER#}) da {@code queryKey}
     * do par veículo e condutor (§10) e devolve a {@code queryKey}. As consultas e evidências anteriores ficam.
     */
    private static String forgetEnrichmentReuse(String vehicleRef, String driverRef) {
        String queryKey = queryKey(vehicleRef, driverRef);
        List<Map<String, AttributeValue>> items = new ArrayList<>(queryByPrefix(QUERIES_TABLE, "REUSE#" + queryKey, "META"));
        items.addAll(DYNAMO_DB.queryPaginator(builder -> builder.tableName(QUERIES_TABLE).consistentRead(true)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("INFLIGHT#" + queryKey))))
                .items().stream().toList());
        for (Map<String, AttributeValue> item : items) {
            DYNAMO_DB.deleteItem(builder -> builder.tableName(QUERIES_TABLE)
                    .key(Map.of("pk", item.get("pk"), "sk", item.get("sk"))));
        }
        return queryKey;
    }

    /** {@code queryKey} da provider (§5): HMAC-SHA256, com o segredo {@code query-key-secret}, das partes do pedido. */
    private static String queryKey(String vehicleRef, String driverRef) {
        List<String> parts = new ArrayList<>();
        parts.add(ORGANIZATION);
        parts.addAll(ENRICHMENT_REQUEST);
        parts.add(vehicleRef);
        parts.add(driverRef);
        return HexFormat.of().formatHex(hmacSha256(queryKeySecret,
                String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmacSha256(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }

    /** T05: a validade do índice passa a ser um instante já vencido, como se o enriquecimento tivesse expirado. */
    private static void expireReuseIndex(String queryKey, String evidenceId) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(QUERIES_TABLE)
                .key(Map.of("pk", AttributeValue.fromS("REUSE#" + queryKey), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET reusableUntil = :expired")
                .conditionExpression("evidenceId = :evidenceId")
                .expressionAttributeValues(Map.of(
                        ":expired", AttributeValue.fromS(PROVIDER_TIMESTAMP.format(Instant.now().minusSeconds(1))),
                        ":evidenceId", AttributeValue.fromS(evidenceId))));
    }

    private static Map<String, AttributeValue> reuseIndex(String queryKey) {
        return single(queryByPrefix(QUERIES_TABLE, "REUSE#" + queryKey, "META"));
    }

    /** Consultas de enriquecimento da {@code queryKey}, de todas as execuções. */
    private static List<Map<String, AttributeValue>> enrichmentQueries(String queryKey) {
        return DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUERIES_TABLE).consistentRead(true)
                        .filterExpression("#type = :query AND queryKey = :queryKey")
                        .expressionAttributeNames(Map.of("#type", "type"))
                        .expressionAttributeValues(Map.of(":query", AttributeValue.fromS("QUERY"),
                                ":queryKey", AttributeValue.fromS(queryKey))))
                .items().stream().toList();
    }

    /** Consultas da provider abertas para a cotação; {@code operation} é {@code ENRICH} ou {@code QUOTE} (§5). */
    private static List<Map<String, AttributeValue>> providerQueriesOf(String quoteId, String operation) {
        return DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUERIES_TABLE).consistentRead(true)
                        .filterExpression("#type = :query AND quoteId = :quoteId AND operation = :operation")
                        .expressionAttributeNames(Map.of("#type", "type"))
                        .expressionAttributeValues(Map.of(":query", AttributeValue.fromS("QUERY"),
                                ":quoteId", AttributeValue.fromS(quoteId),
                                ":operation", AttributeValue.fromS(operation))))
                .items().stream().toList();
    }

    private static Set<String> queryIdsOf(List<Map<String, AttributeValue>> queries) {
        return queries.stream().map(query -> string(query, "queryId")).collect(Collectors.toSet());
    }

    /** Todos os itens da consulta: {@code META}, tentativas e evidências. */
    private static List<Map<String, AttributeValue>> providerHistory(String queryId) {
        return DYNAMO_DB.query(builder -> builder.tableName(QUERIES_TABLE).consistentRead(true)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("QUERY#" + queryId))))
                .items();
    }

    /** Evidência usada pela versão 1 ({@code V#0001#EVIDENCE#<workRef>}, §4). */
    private static Map<String, AttributeValue> enrichmentEvidenceUsed(String quoteId) {
        return single(quoteItems(quoteId, "V#0001#EVIDENCE#"));
    }

    private static long number(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        assertThat(value).as(attribute).isNotNull();
        return Long.parseLong(value.n());
    }

    /**
     * Cadeia da versão 1 pelos instantes gravados pela quote: do {@code POST} ({@code createdAt} da versão) até
     * {@code TERMINAL} ({@code updatedAt} da consolidação) e a espera do enriquecimento, da {@code dispatch}
     * ({@code createdAt} do waiter) até a retomada ({@code receivedAt} da evidência usada).
     */
    private static Chain chainOf(String quoteId) {
        Map<String, AttributeValue> version = versionItem(quoteId);
        Map<String, AttributeValue> used = enrichmentEvidenceUsed(quoteId);
        String workRef = string(used, "sk").substring("V#0001#EVIDENCE#".length());
        Map<String, AttributeValue> waiter = single(queryByPrefix(QUOTES_TABLE, "WAITER#" + workRef, "META"));
        return new Chain(
                Duration.between(Instant.parse(string(version, "createdAt")), Instant.parse(string(version, "updatedAt"))),
                Duration.between(Instant.parse(string(waiter, "createdAt")), Instant.parse(string(used, "receivedAt"))));
    }

    private static Map<String, AttributeValue> versionItem(String quoteId) {
        return versionItem(quoteId, 1);
    }

    private static Map<String, AttributeValue> versionItem(String quoteId, int version) {
        return item(QUOTES_TABLE, Map.of("pk", AttributeValue.fromS("QUOTE#" + quoteId),
                "sk", AttributeValue.fromS("V#%04d".formatted(version))));
    }

    private static void logChain(String label, String quoteId) {
        Chain chain = chainOf(quoteId);
        LOG.info(() -> "%s quoteId=%s postToTerminal=%d ms enrichmentWait=%d ms".formatted(label, quoteId,
                chain.postToTerminal().toMillis(), chain.enrichmentWait().toMillis()));
    }

    /** Tempos de uma versão, medidos pelos instantes gravados nas tabelas. */
    private record Chain(Duration postToTerminal, Duration enrichmentWait) {

        static String summary(List<Chain> chains) {
            List<Long> terminal = chains.stream().map(chain -> chain.postToTerminal().toMillis()).sorted().toList();
            List<Long> enrichment = chains.stream().map(chain -> chain.enrichmentWait().toMillis()).sorted().toList();
            return "postToTerminal min/p50/max=%d/%d/%d ms enrichmentWait min/p50/max=%d/%d/%d ms".formatted(
                    terminal.getFirst(), terminal.get(terminal.size() / 2), terminal.getLast(),
                    enrichment.getFirst(), enrichment.get(enrichment.size() / 2), enrichment.getLast());
        }
    }

    /**
     * Cadeia de uma versão com seguradoras, pelos instantes gravados (§12): do {@code POST} a {@code TERMINAL} e à
     * primeira oferta gravada, que o GET já mostra; por seguradora, da {@code dispatch} à chamada, ao {@code 202}, ao
     * callback recebido e processado pela inbox e à retomada ({@code updatedAt} do {@code RESULT}).
     */
    private static void logInsurerChain(String label, String quoteId) {
        Map<String, AttributeValue> version = versionItem(quoteId);
        Instant posted = Instant.parse(string(version, "createdAt"));
        StringBuilder line = new StringBuilder("%s quoteId=%s postToTerminal=%d ms".formatted(label, quoteId,
                millisBetween(posted, string(version, "updatedAt"))));
        quoteItems(quoteId, "V#0001#OFFER#").stream()
                .map(offer -> string(offer, "createdAt"))
                .min(Comparator.comparing(Instant::parse))
                .ifPresent(first -> line.append(" postToFirstOffer=%d ms".formatted(millisBetween(posted, first))));
        for (Map<String, AttributeValue> result : quoteItems(quoteId, "V#0001#RESULT#")) {
            String insurerId = string(result, "sk").substring("V#0001#RESULT#".length());
            Instant dispatched = Instant.parse(string(waiterOf(quoteId, insurerId), "createdAt"));
            line.append(" %s[dispatchToResume=%d ms".formatted(insurerId,
                    millisBetween(dispatched, string(result, "updatedAt"))));
            String queryId = stringOrNull(result, "queryRef");
            List<Map<String, AttributeValue>> attempts =
                    queryId == null ? List.of() : providerItems(queryId, "ATTEMPT#");
            if (!attempts.isEmpty()) {
                Map<String, AttributeValue> attempt = attempts.getFirst();
                String started = string(attempt, "startedAt");
                line.append(" dispatchToCall=%d ms".formatted(millisBetween(dispatched, started)));
                String acceptedAt = stringOrNull(attempt, "acceptedAt");
                if (acceptedAt != null) {
                    line.append(" callTo202=%d ms".formatted(millisBetween(Instant.parse(started), acceptedAt)));
                }
                for (Map<String, AttributeValue> callback : inboxOf(queryId)) {
                    line.append(" callToCallback=%d ms callbackToProcessed=%d ms".formatted(
                            millisBetween(Instant.parse(started), string(callback, "receivedAt")),
                            millisBetween(Instant.parse(string(callback, "receivedAt")),
                                    string(callback, "processedAt"))));
                }
            }
            line.append(']');
        }
        LOG.info(line::toString);
    }

    private static long millisBetween(Instant from, String to) {
        return Duration.between(from, Instant.parse(to)).toMillis();
    }

    /** Status de cada seguradora no GET da versão, por {@code insurerId}. */
    private static Map<String, String> statusByInsurer(JsonNode version) {
        Map<String, String> statuses = new TreeMap<>();
        version.path("insurerResults").forEach(result ->
                statuses.put(result.path("insurerId").asText(), result.path("status").asText()));
        return statuses;
    }

    private static Map<String, String> namesByInsurer(JsonNode version) {
        Map<String, String> names = new TreeMap<>();
        version.path("insurerResults").forEach(result ->
                names.put(result.path("insurerId").asText(), result.path("insurerName").asText()));
        return names;
    }

    private static JsonNode insurerResult(JsonNode version, String insurerId) {
        for (JsonNode result : version.path("insurerResults")) {
            if (insurerId.equals(result.path("insurerId").asText())) {
                return result;
            }
        }
        throw new AssertionError("the version has no result of " + insurerId + ": " + version);
    }

    /** Ofertas da versão 1 no GET, por seguradora; cada seguradora tem no máximo uma. */
    private static Map<String, JsonNode> offersByInsurer(Accepted accepted) throws Exception {
        Map<String, JsonNode> offers = new TreeMap<>();
        for (JsonNode offer : getOk(accepted.statusUrl() + "/offers").path("items")) {
            assertThat(offers.put(offer.path("insurerId").asText(), offer)).as("one offer per insurer").isNull();
        }
        return offers;
    }

    /** Consulta de cotação ({@code operation = QUOTE}) que a provider abriu para a seguradora na cotação. */
    private static Map<String, AttributeValue> insurerQuery(String quoteId, String insurerId) {
        return single(providerQueriesOf(quoteId, "QUOTE").stream()
                .filter(query -> insurerId.equals(string(query, "insurerId")))
                .toList());
    }

    /** Callbacks da consulta na inbox da provider, pelo {@code queryId} que o processamento grava (§5). */
    private static List<Map<String, AttributeValue>> inboxOf(String queryId) {
        return DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUERIES_TABLE).consistentRead(true)
                        .filterExpression("#type = :inbox AND queryId = :queryId")
                        .expressionAttributeNames(Map.of("#type", "type"))
                        .expressionAttributeValues(Map.of(":inbox", AttributeValue.fromS("INBOX"),
                                ":queryId", AttributeValue.fromS(queryId))))
                .items().stream().toList();
    }

    private static Map<String, AttributeValue> waiterOf(String quoteId, String insurerId) {
        return single(DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUOTES_TABLE).consistentRead(true)
                        .filterExpression("begins_with(pk, :waiter) AND quoteId = :quoteId AND insurerId = :insurerId")
                        .expressionAttributeValues(Map.of(":waiter", AttributeValue.fromS("WAITER#"),
                                ":quoteId", AttributeValue.fromS(quoteId),
                                ":insurerId", AttributeValue.fromS(insurerId))))
                .items().stream().toList());
    }

    /** Callback assinado como o fornecedor assina (§8): {@code sha256=} e o HMAC-SHA256 dos bytes do corpo. */
    private static HttpResponse<String> postCallback(String provider, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + HexFormat.of().formatHex(hmacSha256(callbackSecret, bytes));
        URI callbacks = URI.create(providerApiBase + "/v1/providers/" + provider + "/callbacks");
        return HTTP.send(HttpRequest.newBuilder(callbacks)
                        .header("Content-Type", "application/json")
                        .header("X-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .timeout(Duration.ofMinutes(1))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Publica de novo no barramento o evento gravado no outbox da provider, como uma entrega repetida. */
    private static void republish(Map<String, AttributeValue> outbox) {
        PutEventsResponse response = EVENT_BRIDGE.putEvents(builder -> builder.entries(entry -> entry
                .eventBusName(EVENT_BUS)
                .source("mmda.provider")
                .detailType(string(outbox, "detailType"))
                .detail(string(outbox, "detail"))));
        assertThat(response.failedEntryCount()).isZero();
    }

    /**
     * Espera a retomada registrar o descarte do evento. NOTE: o descarte não grava nada na tabela, então só a linha
     * de log com o {@code eventId} distingue o evento descartado do evento que nunca chegou.
     */
    private static void awaitDiscarded(String eventId, Instant since) {
        String logGroup = "/aws/lambda/" + QUOTE_PREFIX + "-resume";
        String line = "result " + eventId + " already consumed, discarded";
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(LOGS.filterLogEvents(builder -> builder.logGroupName(logGroup)
                                .startTime(since.minusSeconds(5).toEpochMilli()))
                        .events())
                        .as("the resume discarded the republished %s", eventId)
                        .anySatisfy(event -> assertThat(event.message()).contains(line)));
    }

    /**
     * Espera a fila ficar sem mensagens visíveis, em processamento ou atrasadas. NOTE: a primeira leitura espera
     * 5 s, porque cada salto do barramento e do ESM no Floci leva cerca de 1 s (§13) e a fila ainda vazia não diz
     * que a mensagem já passou.
     */
    private static void awaitDrained(String queueName) {
        String queueUrl = SQS.getQueueUrl(builder -> builder.queueName(queueName)).queueUrl();
        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> pendingMessages(queueUrl) == 0);
    }

    private static int pendingMessages(String queueUrl) {
        return SQS.getQueueAttributes(builder -> builder.queueUrl(queueUrl).attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED))
                .attributes().values().stream().mapToInt(Integer::parseInt).sum();
    }

    /**
     * Início da janela de quota corrente (1 min, §12), esperando a virada quando já passaram 10 s dela: assim as
     * cotações do T21 reservam na mesma janela, e a virada fica além do {@code deadlineAt} de cada pedido, então o
     * pedido sem quota conclui sem ser adiado para a janela seguinte (§9).
     */
    private static Instant awaitFreshQuotaWindow() {
        return await().atMost(Duration.ofSeconds(70)).pollInterval(Duration.ofMillis(250))
                .until(Instant::now, now -> now.getEpochSecond() % 60 < 10)
                .truncatedTo(ChronoUnit.MINUTES);
    }

    private static Map<String, AttributeValue> quotaKey(String provider, Instant window) {
        return Map.of("pk", AttributeValue.fromS("QUOTA#" + provider + "#" + CREDENTIAL_REF + "#"
                        + QUOTA_WINDOW.format(window)),
                "sk", AttributeValue.fromS("META"));
    }

    /** Contador {@code QUOTA#} da janela (§5), com o TTL que a provider usa: 1 h depois do fim da janela. */
    private static void putQuota(String provider, Instant window, int used) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(quotaKey(provider, window));
        item.put("type", AttributeValue.fromS("QUOTA"));
        item.put("used", AttributeValue.fromN(Integer.toString(used)));
        item.put("ttl", AttributeValue.fromN(Long.toString(window.plusSeconds(60 + 3600).getEpochSecond())));
        DYNAMO_DB.putItem(builder -> builder.tableName(QUERIES_TABLE).item(item));
    }

    private static long quotaUsed(String provider, Instant window) {
        return number(DYNAMO_DB.getItem(builder -> builder.tableName(QUERIES_TABLE).consistentRead(true)
                .key(quotaKey(provider, window))).item(), "used");
    }

    /** Remove o contador que o T21 fabricou, para os casos seguintes terem a quota real da janela. */
    private static void deleteQuota(String provider, Instant window) {
        DYNAMO_DB.deleteItem(builder -> builder.tableName(QUERIES_TABLE).key(quotaKey(provider, window)));
    }

    /**
     * Espera um ciclo novo do {@code reconciler} da quote, pela linha {@code reconciler report} que ele registra em
     * todo ciclo, e devolve o instante dela.
     */
    private static Instant awaitReconcilerCycle() {
        return awaitLogEvent(QUOTE_RECONCILER_LOG, "reconciler report", Instant.now(), RECONCILER_BUDGET).at();
    }

    private record LogEvent(String message, Instant at) {
    }

    /** Primeira linha do grupo com o texto, registrada depois de {@code after}. */
    private static LogEvent awaitLogEvent(String logGroup, String text, Instant after, Duration budget) {
        FilteredLogEvent event = await().atMost(budget).pollInterval(Duration.ofSeconds(2))
                .until(() -> logEvents(logGroup, text, after).stream()
                        .filter(found -> found.timestamp() > after.toEpochMilli())
                        .min(Comparator.comparing(FilteredLogEvent::timestamp)), Optional::isPresent)
                .orElseThrow();
        return new LogEvent(event.message(), Instant.ofEpochMilli(event.timestamp()));
    }

    private static void awaitLog(String logGroup, String text, Instant since, Duration budget) {
        await().atMost(budget).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> assertLog(logGroup, text, since));
    }

    private static void assertLog(String logGroup, String text, Instant since) {
        assertThat(logEvents(logGroup, text, since)).as("%s logged '%s' since %s", logGroup, text, since).isNotEmpty();
    }

    private static List<FilteredLogEvent> logEvents(String logGroup, String text, Instant since) {
        return LOGS.filterLogEventsPaginator(builder -> builder.logGroupName(logGroup)
                        .startTime(since.minusSeconds(5).toEpochMilli()))
                .events().stream()
                .filter(event -> event.message().contains(text))
                .toList();
    }

    /** {@code workRef} da quote (§3): {@code wr_} e 32 hexadecimais do SHA-256 de {@code quoteId|version|kind|insurerId}. */
    private static String workRef(String quoteId, int version, String kind, String insurerId) {
        return "wr_" + sha256Hex(String.join("|", quoteId, Integer.toString(version), kind, insurerId)).substring(0, 32);
    }

    /** Ocorrência própria da quote (§3), pelo {@code issueId} do {@code QuoteIssues}: {@code quote|tipo|alvo}. */
    private static Optional<Map<String, AttributeValue>> quoteIssue(String type, String target) {
        String issueId = "iss_" + sha256Hex("quote|" + type + "|" + target).substring(0, 32);
        return queryByPrefix(QUOTES_TABLE, "ISSUE#" + issueId, "META").stream().findFirst();
    }

    /** Ocorrência da provider (§5), pelo {@code issueId} {@code is_} e o SHA-256 de {@code tipo|alvo}. */
    private static Optional<Map<String, AttributeValue>> providerIssue(String type, String target) {
        String issueId = "is_" + sha256Hex(type + "|" + target).substring(0, 32);
        return queryByPrefix(QUERIES_TABLE, "ISSUE#" + issueId, "META").stream().findFirst();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /** {@code eventId} determinístico das duas soluções (§3): UUIDv5 do nome no namespace URL da RFC 9562. */
    private static String uuidV5(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(EVENT_ID_NAMESPACE.getMostSignificantBits())
                    .putLong(EVENT_ID_NAMESPACE.getLeastSignificantBits()).array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bytes.getLong(), bytes.getLong()).toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-1 is not available", exception);
        }
    }

    private static Map<String, AttributeValue> metaKey(String pk) {
        return Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS("META"));
    }

    /** Item pela chave, com leitura consistente; vazio quando não existe. */
    private static Map<String, AttributeValue> item(String table, Map<String, AttributeValue> key) {
        return DYNAMO_DB.getItem(builder -> builder.tableName(table).consistentRead(true).key(key)).item();
    }

    /** T13: troca o token cifrado do waiter só se ele ainda é o esperado. */
    private static void replaceToken(Map<String, AttributeValue> waiterKey, String expected, String replacement) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(QUOTES_TABLE).key(waiterKey)
                .updateExpression("SET tokenCiphertext = :replacement")
                .conditionExpression("tokenCiphertext = :expected")
                .expressionAttributeValues(Map.of(":replacement", AttributeValue.fromS(replacement),
                        ":expected", AttributeValue.fromS(expected))));
    }

    /** T13: devolve o token original se o caso parou com o token fabricado no waiter. */
    private static void restoreToken(Map<String, AttributeValue> waiterKey, String originalToken) {
        if (waiterKey == null) {
            return;
        }
        try {
            replaceToken(waiterKey, UNREADABLE_TOKEN, originalToken);
        } catch (ConditionalCheckFailedException alreadyRestored) {
            LOG.fine(() -> "T13 token already restored: " + alreadyRestored.getMessage());
        }
    }

    /** Espera o resultado tardio da seguradora na versão 1 ({@code V#0001#LATE#<insurerId>#<queryId>}, §4). */
    private static Map<String, AttributeValue> awaitLate(String quoteId, String insurerId, String queryId) {
        return await().atMost(RECONCILER_BUDGET).pollInterval(Duration.ofSeconds(5))
                .until(() -> quoteItems(quoteId, "V#0001#LATE#" + insurerId + "#" + queryId), items -> items.size() == 1)
                .getFirst();
    }

    /** A cotação terminal não reabre: versão, resultados, ofertas e resumo como antes do resultado tardio (T14, T16). */
    private static void assertTerminalUnchanged(Accepted accepted, JsonNode version, Map<String, JsonNode> offers,
            List<Map<String, AttributeValue>> versionBefore) throws Exception {
        assertThat(quoteItems(accepted.quoteId(), "V#0001").stream()
                .filter(item -> !string(item, "sk").startsWith("V#0001#LATE#"))
                .toList()).containsExactlyInAnyOrderElementsOf(versionBefore);
        JsonNode after = getOk(accepted.statusUrl());
        for (String field : List.of("phase", "outcome", "outcomeReason", "currentVersion")) {
            assertThat(after.path(field)).as(field).isEqualTo(version.path(field));
        }
        assertThat(statusByInsurer(after)).isEqualTo(statusByInsurer(version));
        assertThat(offersByInsurer(accepted).keySet()).isEqualTo(offers.keySet());
    }

    /** {@code RedrivePolicy} implantada: a fila leva à sua DLQ depois de {@code maxReceiveCount} recebimentos. */
    private static void assertRedrivePolicy(String queueName, String deadLetterQueueName, int maxReceiveCount)
            throws Exception {
        String queueUrl = SQS.getQueueUrl(builder -> builder.queueName(queueName)).queueUrl();
        JsonNode redrive = JSON.readTree(SQS.getQueueAttributes(builder -> builder.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.REDRIVE_POLICY)).attributes().get(QueueAttributeName.REDRIVE_POLICY));
        assertThat(redrive.path("deadLetterTargetArn").asText()).endsWith(":" + deadLetterQueueName);
        assertThat(redrive.path("maxReceiveCount").asText()).isEqualTo(Integer.toString(maxReceiveCount));
    }

    /** Põe o corpo na DLQ como a {@code RedrivePolicy} o deixaria: a entrega original, sem {@code dlqAttempt}. */
    private static void sendToDeadLetterQueue(String deadLetterQueueName, String body) {
        String queueUrl = SQS.getQueueUrl(builder -> builder.queueName(deadLetterQueueName)).queueUrl();
        SQS.sendMessage(builder -> builder.queueUrl(queueUrl).messageBody(body));
    }

    /** Envelope que a regra do barramento entrega na fila, com o {@code detail} gravado no outbox. */
    private static String envelope(String source, Map<String, AttributeValue> outbox) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", "0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("detail-type", string(outbox, "detailType"));
        envelope.put("source", source);
        envelope.put("account", "000000000000");
        envelope.put("time", SECONDS.format(Instant.now()) + "Z");
        envelope.put("region", "us-east-1");
        envelope.put("resources", List.of());
        envelope.put("detail", JSON.readTree(string(outbox, "detail")));
        return JSON.writeValueAsString(envelope);
    }

    /** {@code QueryReconcileRequested} no contrato da quote (AsyncAPI), como a regra o entrega na provider. */
    private static String reconcileRequested(String operationId, String queryId) throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", UUID.randomUUID().toString());
        detail.put("occurredAt", Instant.now().toString());
        detail.put("schemaVersion", "1");
        detail.put("operationId", operationId);
        detail.put("organizationId", ORGANIZATION);
        detail.put("queryId", queryId);
        detail.put("reason", "T31_DEAD_LETTER_REPROCESSING");
        detail.put("requestedBy", USERNAME);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", "0");
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("detail-type", "QueryReconcileRequested");
        envelope.put("source", "mmda.quote");
        envelope.put("account", "000000000000");
        envelope.put("time", SECONDS.format(Instant.now()) + "Z");
        envelope.put("region", "us-east-1");
        envelope.put("resources", List.of());
        envelope.put("detail", detail);
        return JSON.writeValueAsString(envelope);
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(element -> texts.add(element.asText()));
        return texts;
    }

    private static String stringOrNull(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        return value == null ? null : value.s();
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

    /** Pedido completo da nova versão ({@code CreateVersionRequest} do OpenAPI), com a versão atual lida. */
    private static Map<String, Object> versionRequest(int expectedVersion, String vehicleRef, String usage,
            String driverRef) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedVersion", expectedVersion);
        request.putAll(request(vehicleRef, usage, driverRef));
        return request;
    }

    private static HttpResponse<String> postVersion(String quoteId, String idempotencyKey, String body) {
        return call(WRITER, "POST", "/v1/quotes/" + quoteId + "/versions", idempotencyKey, body);
    }

    private static Accepted create(String idempotencyKey, String body) throws Exception {
        HttpResponse<String> response = post(idempotencyKey, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return accepted(response);
    }

    /** {@code QuoteAccepted} de um {@code 202}, com o {@code Location} igual ao {@code statusUrl}. */
    private static Accepted accepted(HttpResponse<String> response) throws Exception {
        JsonNode accepted = JSON.readTree(response.body());
        assertThat(response.headers().firstValue("Location")).contains(accepted.path("statusUrl").asText());
        return new Accepted(accepted.path("quoteId").asText(), accepted.path("version").asInt(),
                accepted.path("statusUrl").asText());
    }

    private static HttpResponse<String> post(String idempotencyKey, String body) {
        return call(WRITER, "POST", "/v1/quotes", idempotencyKey, body);
    }

    private static JsonNode getOk(String path) throws Exception {
        return okJson(WRITER, path);
    }

    /** Troca com a API da quote como {@code user}, conferida contra o contrato pelo {@link ContractClient}. */
    private static HttpResponse<String> call(User user, String method, String path, String idempotencyKey,
            String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return api.send(TOKENS.get(user), method, path, headers, body);
    }

    private static JsonNode okJson(User user, String path) throws Exception {
        HttpResponse<String> response = call(user, "GET", path, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertNoRawData(response.body());
        return JSON.readTree(response.body());
    }

    /** Nenhuma resposta traz token, payload bruto ou atributo interno do item (§12 do PRD). */
    private static void assertNoRawData(String body) {
        assertThat(RAW_FIELDS).allSatisfy(field -> assertThat(body).doesNotContain("\"" + field + "\":"));
    }

    private static HttpResponse<String> select(User user, Accepted accepted, String idempotencyKey, String offerId)
            throws Exception {
        return call(user, "POST", accepted.statusUrl() + "/selection", idempotencyKey,
                JSON.writeValueAsString(Map.of("offerId", offerId)));
    }

    private static HttpResponse<String> reconcile(User user, String queryId, String idempotencyKey, String reason)
            throws Exception {
        return call(user, "POST", "/v1/operations/queries/" + queryId + "/reconcile", idempotencyKey,
                JSON.writeValueAsString(Map.of("reason", reason)));
    }

    /** Erro padrão do contrato: o código, uma mensagem e o {@code correlationId}. */
    private static JsonNode assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode error = JSON.readTree(response.body());
        assertThat(error.path("code").asText()).as(response.body()).isEqualTo(code);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("correlationId").asText()).isNotBlank();
        return error;
    }

    /** Primeira página de ofertas não vazia da versão, lida a cada 250 ms para medir o tempo até a oferta (§15). */
    private static JsonNode awaitFirstOffer(Accepted accepted, Duration budget) {
        return await().atMost(budget).pollInterval(Duration.ofMillis(250))
                .until(() -> getOk(accepted.statusUrl() + "/offers"), page -> !page.path("items").isEmpty());
    }

    /** Itens de todas as páginas, seguindo o {@code nextCursor} com os mesmos filtros. */
    private static List<JsonNode> allItems(User user, String path) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 200; page++) {
            String separator = path.contains("?") ? "&" : "?";
            JsonNode body = okJson(user, cursor == null ? path : path + separator + "cursor=" + cursor);
            items.addAll(itemsOf(body));
            if (body.path("nextCursor").isNull()) {
                return items;
            }
            cursor = body.path("nextCursor").asText();
        }
        throw new IllegalStateException("more than 200 pages at " + path);
    }

    private static List<JsonNode> itemsOf(JsonNode page) {
        List<JsonNode> items = new ArrayList<>();
        page.path("items").forEach(items::add);
        return items;
    }

    private static List<String> ids(List<JsonNode> items) {
        return items.stream().map(item -> item.path("id").asText()).toList();
    }

    private static JsonNode fixture(String name) throws Exception {
        return JSON.readTree(FIXTURES.resolve(name).toFile());
    }

    private static JsonNode organizationOf(JsonNode catalog, String organizationId) {
        for (JsonNode organization : catalog.path("organizations")) {
            if (organization.path("id").asText().equals(organizationId)) {
                return organization;
            }
        }
        throw new IllegalStateException("organization " + organizationId + " is not in catalog.json");
    }

    /** {@code id} e {@code label} das entradas do {@code catalog.json}, na ordem do arquivo. */
    private static List<JsonNode> idsAndLabels(JsonNode entries) {
        List<JsonNode> projected = new ArrayList<>();
        entries.forEach(entry -> projected.add(JSON.createObjectNode()
                .put("id", entry.path("id").asText()).put("label", entry.path("label").asText())));
        return projected;
    }

    /** Entradas cujo rótulo ou id contém o termo, sem acento nem maiúscula, como a busca do catálogo (§11). */
    private static List<String> matching(JsonNode entries, String search) {
        String term = folded(search);
        List<String> found = new ArrayList<>();
        entries.forEach(entry -> {
            if (folded(entry.path("label").asText()).contains(term) || folded(entry.path("id").asText()).contains(term)) {
                found.add(entry.path("id").asText());
            }
        });
        return found;
    }

    private static String folded(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static JsonNode coverageProfile(String code) throws Exception {
        for (JsonNode profile : JSON.readTree(CATALOG.toFile()).path("coverageProfiles")) {
            if (profile.path("code").asText().equals(code)) {
                return profile;
            }
        }
        throw new IllegalStateException("coverage profile " + code + " is not in catalog.json");
    }

    /** T18: troca o {@code validUntil} da oferta; o {@code finally} devolve o original. */
    private static void setValidUntil(Map<String, AttributeValue> offerKey, String validUntil) {
        DYNAMO_DB.updateItem(builder -> builder.tableName(QUOTES_TABLE).key(offerKey)
                .updateExpression("SET validUntil = :validUntil")
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeValues(Map.of(":validUntil", AttributeValue.fromS(validUntil))));
    }

    /** Chave do registro de idempotência da quote (§4): organização, operação, escopo e a chave do cliente. */
    private static String idempotencyPk(String organizationId, String operation, String scope, String key) {
        return "IDEMPOTENCY#" + organizationId + "#" + operation + "#" + scope + "#" + key;
    }

    /** Nenhuma intenção de proposta: sem {@code SELECTION}, {@code selectionId}, idempotência nem outbox. */
    private static void assertNoSelectionRecorded(String quoteId, String... idempotencyPks) {
        assertThat(queryByPrefix(QUOTES_TABLE, "QUOTE#" + quoteId, "V#")).as("SELECTION items")
                .noneMatch(item -> string(item, "sk").endsWith("#SELECTION"));
        assertThat(item(QUOTES_TABLE, metaKey("QUOTE#" + quoteId))).doesNotContainKey("selectionId");
        for (String pk : idempotencyPks) {
            assertThat(item(QUOTES_TABLE, metaKey(pk))).as(pk).isEmpty();
        }
        assertThat(outboxOf("OfferSelected", "quoteId", quoteId)).as("OfferSelected outbox").isEmpty();
    }

    /** Outbox da quote com o {@code detailType} e o atributo pedidos. */
    private static List<Map<String, AttributeValue>> outboxOf(String detailType, String attribute, String value) {
        return DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUOTES_TABLE).consistentRead(true)
                        .filterExpression("begins_with(pk, :outbox) AND detailType = :type AND #attribute = :value")
                        .expressionAttributeNames(Map.of("#attribute", attribute))
                        .expressionAttributeValues(Map.of(":outbox", AttributeValue.fromS("OUTBOX#"),
                                ":type", AttributeValue.fromS(detailType), ":value", AttributeValue.fromS(value))))
                .items().stream().toList();
    }

    private static List<Map<String, AttributeValue>> operationsOfQuery(String queryId) {
        return DYNAMO_DB.scanPaginator(builder -> builder.tableName(QUOTES_TABLE).consistentRead(true)
                        .filterExpression("begins_with(pk, :operation) AND queryId = :queryId")
                        .expressionAttributeValues(Map.of(":operation", AttributeValue.fromS("OPERATION#"),
                                ":queryId", AttributeValue.fromS(queryId))))
                .items().stream().toList();
    }

    private record Race(List<HttpResponse<String>> selections, List<HttpResponse<String>> versions) {
    }

    /** T29: {@link #T29_CONTENDERS} seleções da mesma oferta e o mesmo número de novas versões, soltos juntos. */
    private static Race race(ExecutorService executor, Accepted current, String offerId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        String versionBody = JSON.writeValueAsString(
                versionRequest(current.version(), BETA_NEEDS_INFO, "PERSONAL", DRIVER));
        List<Future<HttpResponse<String>>> selections = new ArrayList<>();
        List<Future<HttpResponse<String>>> versions = new ArrayList<>();
        for (int contender = 0; contender < T29_CONTENDERS; contender++) {
            String selectionKey = newKey();
            String versionKey = newKey();
            Callable<HttpResponse<String>> selection = () -> {
                start.await();
                return select(WRITER, current, selectionKey, offerId);
            };
            Callable<HttpResponse<String>> version = () -> {
                start.await();
                return postVersion(current.quoteId(), versionKey, versionBody);
            };
            selections.add(executor.submit(selection));
            versions.add(executor.submit(version));
        }
        start.countDown();
        List<HttpResponse<String>> selected = new ArrayList<>();
        for (Future<HttpResponse<String>> future : selections) {
            selected.add(future.get());
        }
        List<HttpResponse<String>> created = new ArrayList<>();
        for (Future<HttpResponse<String>> future : versions) {
            created.add(future.get());
        }
        return new Race(selected, created);
    }

    private static List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).toList();
    }

    private static String selectionIdOf(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body()).path("selectionId").asText();
        } catch (Exception unreadable) {
            throw new IllegalStateException(response.body(), unreadable);
        }
    }

    /**
     * {@code OperationIssueRaised} de uma {@code UNKNOWN_QUERY} no contrato da provider (AsyncAPI), publicado no
     * barramento como a provider publica; é o estado fabricado da reconciliação manual.
     */
    private static void publishProviderIssue(String eventId, String issueId, String queryId, String quoteId)
            throws Exception {
        String now = PROVIDER_TIMESTAMP.format(Instant.now());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventId", eventId);
        detail.put("occurredAt", now);
        detail.put("schemaVersion", "1");
        detail.put("issueId", issueId);
        detail.put("organizationId", ORGANIZATION);
        detail.put("type", "UNKNOWN_QUERY");
        detail.put("status", "OPEN");
        detail.put("queryId", queryId);
        detail.put("quoteId", quoteId);
        detail.put("firstSeenAt", now);
        detail.put("lastSeenAt", now);
        detail.put("safeSummary", "Consulta sem resposta conhecida (ocorrência publicada pelo e2e)");
        detail.put("reconcilable", true);
        detail.put("revision", 1);
        String json = JSON.writeValueAsString(detail);
        PutEventsResponse response = EVENT_BRIDGE.putEvents(builder -> builder.entries(entry -> entry
                .eventBusName(EVENT_BUS).source("mmda.provider").detailType("OperationIssueRaised").detail(json)));
        assertThat(response.failedEntryCount()).as(response.toString()).isZero();
    }

    private static JsonNode issueOf(List<JsonNode> issues, String issueId) {
        return issues.stream().filter(issue -> issue.path("id").asText().equals(issueId)).findFirst()
                .orElseThrow(() -> new AssertionError("issue " + issueId + " not listed"));
    }

    private static void deleteQuoteItem(String pk) {
        DYNAMO_DB.deleteItem(builder -> builder.tableName(QUOTES_TABLE).key(metaKey(pk)));
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
     * Garante cada usuário de teste exatamente nos grupos da organização e das permissões dele (APIs administrativas
     * do Cognito) e guarda o access token obtido com {@code USER_PASSWORD_AUTH}, que só a Lambda da API valida.
     */
    private static void signIn() {
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
            USERS.stream().flatMap(user -> user.groups().stream()).distinct()
                    .filter(group -> !groups.contains(group))
                    .forEach(group -> cognito.createGroup(builder -> builder.userPoolId(poolId).groupName(group)));
            for (User user : USERS) {
                try {
                    cognito.adminGetUser(builder -> builder.userPoolId(poolId).username(user.username()));
                } catch (UserNotFoundException missing) {
                    cognito.adminCreateUser(builder -> builder.userPoolId(poolId).username(user.username())
                            .messageAction(MessageActionType.SUPPRESS));
                }
                cognito.adminSetUserPassword(builder -> builder.userPoolId(poolId).username(user.username())
                        .password(PASSWORD).permanent(true));
                AdminListGroupsForUserResponse current = cognito.adminListGroupsForUser(builder -> builder
                        .userPoolId(poolId).username(user.username()).limit(60));
                current.groups().stream().map(GroupType::groupName).filter(group -> !user.groups().contains(group))
                        .forEach(group -> cognito.adminRemoveUserFromGroup(builder -> builder.userPoolId(poolId)
                                .username(user.username()).groupName(group)));
                user.groups().forEach(group -> cognito.adminAddUserToGroup(builder -> builder.userPoolId(poolId)
                        .username(user.username()).groupName(group)));
                TOKENS.put(user, cognito.initiateAuth(builder -> builder.clientId(clientId)
                                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                                .authParameters(Map.of("USERNAME", user.username(), "PASSWORD", PASSWORD)))
                        .authenticationResult().accessToken());
            }
        }
    }

    /** Usuário de teste; a organização vem do primeiro grupo, {@code org_<organizationId>}. */
    private record User(String username, List<String> groups) {

        /** Permissões do contrato concedidas pelos grupos {@code perm_*} (§11 do desenho). */
        List<String> permissions() {
            return groups.stream().filter(group -> group.startsWith("perm_"))
                    .map(group -> group.substring("perm_".length()))
                    .map(permission -> {
                        int split = permission.indexOf('_');
                        return permission.substring(0, split) + ":" + permission.substring(split + 1);
                    })
                    .toList();
        }
    }

    private record Accepted(String quoteId, int version, String statusUrl) {

        /** Identidade devolvida pela API; a fase pode ter avançado entre um envio e outro. */
        String identity() {
            return quoteId + "#" + version + "#" + statusUrl;
        }
    }
}

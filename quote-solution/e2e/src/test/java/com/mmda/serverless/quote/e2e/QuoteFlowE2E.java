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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionListItem;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;

/**
 * Ponta a ponta do M1 e do M2 (§14) no Floci, com as três stacks implantadas e as esperas no orçamento do stage
 * (§9), sem supor funções aquecidas. O catálogo é fixo e a {@code queryKey} depende só de organização, veículo e
 * condutor (§5): os casos de reuso apagam antes o {@code REUSE#} e o {@code INFLIGHT#} do próprio par (§10), e
 * cada caso usa um par diferente.
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
    private static final String DRIVER_ANA = "driver-acme-ana";
    private static final String VAN = "vehicle-van-2022";
    private static final String SEDAN = "vehicle-sedan-2019";
    private static final String CLASSIC = "vehicle-classic-1998";
    private static final String SALVAGE = "vehicle-salvage";
    private static final String UNKNOWN_TO_THE_PROVIDER = "vehicle-not-found";
    private static final String ENRICHMENT_DOWN = "vehicle-enrichment-down";
    /** Partes fixas da {@code queryKey} (§5), como a quote as envia no {@code EnrichmentRequested} (§3). */
    private static final List<String> ENRICHMENT_REQUEST = List.of("enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1");
    private static final String QUERY_KEY_SECRET = "provider-solution/" + STAGE + "/query-key-secret";
    private static final int CONCURRENT_QUOTES = Integer.getInteger("e2e.concurrentQuotes", 12);
    private static final String DECIMAL = "^\\d+(\\.\\d+)?$";
    private static final String ORGANIZATION = "org-acme";
    /** Dois disparos de {@code rate(1 minute)} e a publicação; o Floci dispara a cada 60 a 70 s. */
    private static final Duration RECONCILER_BUDGET = Duration.ofMinutes(3);
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

    private static StageParameters stage;
    private static String apiBase;
    private static String accessToken;
    private static String stateMachineArn;
    private static String desiredStartDate;
    private static byte[] queryKeySecret;

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
        try (SecretsManagerClient secrets = SecretsManagerClient.create()) {
            queryKeySecret = secrets.getSecretValue(builder -> builder.secretId(QUERY_KEY_SECRET)).secretString()
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void closeClients() {
        DYNAMO_DB.close();
        SFN.close();
        S3.close();
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
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(queryKeySecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
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
        return DYNAMO_DB.getItem(builder -> builder.tableName(QUOTES_TABLE).consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS("QUOTE#" + quoteId), "sk", AttributeValue.fromS("V#0001"))))
                .item();
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

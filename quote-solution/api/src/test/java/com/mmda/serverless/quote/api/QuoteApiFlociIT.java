package com.mmda.serverless.quote.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.QuoteKeys;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** API completa contra a tabela e o user pool reais do Floci, com access tokens emitidos pelo Cognito. */
@QuarkusTest
@WithTestResource(FlociResource.class)
class QuoteApiFlociIT {

    private static final String BODY = QuoteApiContractTest.CREATE_BODY;

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    ObjectMapper objectMapper;

    // WHY: o padrão só existe para a classe validar na inicialização dos testes do surefire, sem o Floci
    @ConfigProperty(name = "it.access-token.ana-writer", defaultValue = "unset")
    String writerToken;

    @ConfigProperty(name = "it.access-token.rui-reader", defaultValue = "unset")
    String readerToken;

    @ConfigProperty(name = "it.access-token.carla-globex", defaultValue = "unset")
    String globexToken;

    @ConfigProperty(name = "it.id-token.ana-writer", defaultValue = "unset")
    String writerIdToken;

    @ConfigProperty(name = "it.access-token.olga-operator", defaultValue = "unset")
    String operatorToken;

    @ConfigProperty(name = "it.access-token.gil-globex-operator", defaultValue = "unset")
    String globexOperatorToken;

    @Test
    void creationStoresQuoteVersionIdempotencyAndBothOutboxEventsInOneTransaction() throws Exception {
        String key = UUID.randomUUID().toString();
        String quoteId = create(writerToken, key, BODY).path("quoteId");

        Map<String, AttributeValue> idempotency = item("IDEMPOTENCY#org-acme#CREATE_QUOTE#-#" + key, "META");
        assertThat(idempotency.get("quoteId").s()).isEqualTo(quoteId);
        assertThat(idempotency.get("requestHash").s()).hasSize(64);

        Map<String, AttributeValue> meta = item("QUOTE#" + quoteId, "META");
        assertThat(meta.get("organizationId").s()).isEqualTo("org-acme");
        assertThat(meta.get("currentVersion").n()).isEqualTo("1");
        assertThat(meta.get("phase").s()).isEqualTo("RECEIVED");
        assertThat(meta.get("gsi1pk").s()).isEqualTo("ORG#org-acme");

        Map<String, AttributeValue> version = item("QUOTE#" + quoteId, "V#0001");
        assertThat(version.get("phase").s()).isEqualTo("RECEIVED");
        assertThat(version.get("executionName").s()).isEqualTo("q-" + quoteId + "-v1");
        assertThat(version.get("gsi2pk").s()).isEqualTo("ACTIVE_VERSION");
        assertThat(Duration.between(Instant.parse(version.get("createdAt").s()), Instant.parse(version.get("deadlineAt").s())))
                .isEqualTo(Duration.ofSeconds(300));
        JsonNode snapshot = objectMapper.readTree(version.get("snapshot").s());
        assertThat(snapshot.at("/risk/vehicle/label").asText()).isEqualTo("Hatch 1.0 2021");
        assertThat(snapshot.at("/risk/principalDriver/licenseDate").asText()).isEqualTo("2012-03-15");
        assertThat(snapshot.at("/coverage/code").asText()).isEqualTo("COMPREHENSIVE");

        List<Map<String, AttributeValue>> outbox = outboxOf(quoteId);
        assertThat(outbox).extracting(item -> item.get("detailType").s())
                .containsExactlyInAnyOrder("StartQuoteRequested", "QuoteAccepted");
        assertThat(outbox).allSatisfy(item -> {
            assertThat(item.get("gsi2pk").s()).isEqualTo("OUTBOX_PENDING");
            assertThat(item.get("type").s()).isEqualTo("OUTBOX");
        });
    }

    @Test
    void t02_resubmissionReturnsSameQuote() {
        String key = UUID.randomUUID().toString();
        String first = create(writerToken, key, BODY).path("quoteId");
        String reformatted = """
                {"desiredStartDate":"2026-12-01","coverageProfile":"COMPREHENSIVE",
                  "risk":{"principalDriverRef":"driver-acme-ana","usage":"PERSONAL","vehicleRef":"vehicle-hatch-2021"},
                  "applicantRef":"customer-acme-ana","product":"AUTO"}
                """;

        String second = create(writerToken, key, reformatted).path("quoteId");

        assertThat(second).isEqualTo(first);
        assertThat(outboxOf(first)).hasSize(2);
        assertThat(outboxOf(first).stream().filter(item -> item.get("detailType").s().equals("StartQuoteRequested")))
                .hasSize(1);
    }

    @Test
    void t03_sameKeyDifferentBodyReturnsConflict() {
        String key = UUID.randomUUID().toString();
        String quoteId = create(writerToken, key, BODY).path("quoteId");
        String snapshotBefore = item("QUOTE#" + quoteId, "V#0001").get("snapshot").s();

        given().header("Authorization", "Bearer " + writerToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(BODY.replace("vehicle-hatch-2021", "vehicle-sedan-2019"))
                .when().post("/v1/quotes")
                .then().statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_CONFLICT"));

        assertThat(item("QUOTE#" + quoteId, "V#0001").get("snapshot").s()).isEqualTo(snapshotBefore);
        assertThat(outboxOf(quoteId)).hasSize(2);
    }

    @Test
    void sameKeyInAnotherOrganizationCreatesAnIndependentQuote() {
        String key = UUID.randomUUID().toString();
        String acme = create(writerToken, key, BODY).path("quoteId");
        String globexBody = """
                {"product":"AUTO","applicantRef":"customer-globex-carla",
                 "risk":{"vehicleRef":"vehicle-globex-suv-2022","usage":"PERSONAL","principalDriverRef":"driver-globex-carla"},
                 "coverageProfile":"THIRD_PARTY","desiredStartDate":"2026-12-01"}
                """;

        String globex = create(globexToken, key, globexBody).path("quoteId");

        assertThat(globex).isNotEqualTo(acme);
    }

    @Test
    void versionIsReadableOnlyByTheOwnerOrganizationWithItsPermissions() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");

        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("phase", equalTo("RECEIVED"))
                .body("outcome", nullValue())
                .body("currentVersion", equalTo(1))
                .body("snapshot.risk.vehicleRef", equalTo("vehicle-hatch-2021"))
                .body("allowedActions", contains("CREATE_VERSION"));
        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("allowedActions", empty());
        given().header("Authorization", "Bearer " + globexToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(404);
        given().header("Authorization", "Bearer " + globexToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(404);
    }

    @Test
    void offersShowValidityAndAvailabilityDerivedAtReadTime() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        putOffer(quoteId, "of_a", Instant.now().plusSeconds(600));
        putOffer(quoteId, "of_b", Instant.now().minusSeconds(1));

        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(200)
                .body("revision", equalTo(2))
                .body("items.id", contains("of_a", "of_b"))
                .body("items.selectable", contains(true, false))
                .body("items[1].unavailabilityReason", equalTo("OFFER_EXPIRED"));
        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("allowedActions", contains("CREATE_VERSION", "SELECT_OFFER"));
        given().header("Authorization", "Bearer " + readerToken).queryParam("limit", 1)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(200)
                .body("items.id", contains("of_a"))
                .body("nextCursor", equalTo(QuoteViews.encodeCursor("of_a")));
    }

    @Test
    void t08_offerOfOneInsurerIsVisibleWhileTheOtherWaitsAndStaysAfterThePartialOutcome() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        setVersion(quoteId, "QUOTING", null);
        putResult(quoteId, "alpha", "Alpha Seguros", "QUOTED", null);
        putResult(quoteId, "beta", "Beta Seguros", "PROCESSING", null);
        putOffer(quoteId, "of_alpha", Instant.now().plusSeconds(1800));

        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("phase", equalTo("QUOTING"))
                .body("outcome", nullValue())
                .body("retryAfterSeconds", equalTo(3))
                .body("insurerResults.insurerId", contains("alpha", "beta"))
                .body("insurerResults.insurerName", contains("Alpha Seguros", "Beta Seguros"))
                .body("insurerResults.status", contains("QUOTED", "PROCESSING"));
        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(200)
                .body("items.insurerId", contains("alpha"))
                .body("items[0].insurerName", equalTo("Alpha Seguros"))
                .body("items[0].selectable", equalTo(true));

        putResult(quoteId, "beta", "Beta Seguros", "TIMED_OUT", "STEP_TIMEOUT");
        setVersion(quoteId, "TERMINAL", "PARTIAL");

        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("phase", equalTo("TERMINAL"))
                .body("outcome", equalTo("PARTIAL"))
                .body("outcomeReason", nullValue())
                .body("retryAfterSeconds", nullValue())
                .body("insurerResults.status", contains("QUOTED", "TIMED_OUT"))
                .body("insurerResults[1].reasonCode", equalTo("STEP_TIMEOUT"));
        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(200)
                .body("items.id", contains("of_alpha"));
    }

    @Test
    void t07_declineReasonIsShownNextToTheOfferOfTheOtherInsurer() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        putResult(quoteId, "alpha", "Alpha Seguros", "QUOTED", null);
        putResult(quoteId, "beta", "Beta Seguros", "DECLINED", "RISK_NOT_ACCEPTED");
        putOffer(quoteId, "of_alpha", Instant.now().plusSeconds(1800));
        setVersion(quoteId, "TERMINAL", "COMPLETED");

        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("outcome", equalTo("COMPLETED"))
                .body("outcomeReason", nullValue())
                .body("insurerResults.status", contains("QUOTED", "DECLINED"))
                .body("insurerResults[1].reasonCode", equalTo("RISK_NOT_ACCEPTED"));
    }

    @Test
    void cognitoAccessTokenIsAcceptedAndIdTokenIsRejected() {
        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/me")
                .then().statusCode(200)
                .body("organizationId", equalTo("org-acme"))
                .body("displayName", equalTo("ana-writer"))
                .body("permissions", contains("quotes:read", "quotes:write", "offers:select"));
        given().header("Authorization", "Bearer " + writerIdToken)
                .when().get("/v1/me")
                .then().statusCode(401)
                .body("code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void readerCannotCreateAQuote() {
        given().header("Authorization", "Bearer " + readerToken).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON).body(BODY)
                .when().post("/v1/quotes")
                .then().statusCode(403);
    }

    @Test
    void t17_newVersionSupersedesTheCurrentOneAndBlocksSelectionOnTheOldOne() throws Exception {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        putOffer(quoteId, "of_a", Instant.now().plusSeconds(600));
        String key = UUID.randomUUID().toString();

        given().header("Authorization", "Bearer " + writerToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(QuoteApiContractTest.VERSION_BODY)
                .when().post("/v1/quotes/" + quoteId + "/versions")
                .then().statusCode(202)
                .header("Location", equalTo("/v1/quotes/" + quoteId + "/versions/2"))
                .body("version", equalTo(2))
                .body("phase", equalTo("RECEIVED"));

        Map<String, AttributeValue> meta = item("QUOTE#" + quoteId, "META");
        assertThat(meta.get("currentVersion").n()).isEqualTo("2");
        assertThat(meta.get("phase").s()).isEqualTo("RECEIVED");
        assertThat(meta.get("offerCount").n()).isEqualTo("0");
        Map<String, AttributeValue> superseded = item("QUOTE#" + quoteId, "V#0001");
        assertThat(superseded).containsKey("supersededAt");
        assertThat(superseded.get("revision").n()).isEqualTo("2");
        Map<String, AttributeValue> current = item("QUOTE#" + quoteId, "V#0002");
        assertThat(current.get("gsi2pk").s()).isEqualTo("ACTIVE_VERSION");
        assertThat(current.get("executionName").s()).isEqualTo("q-" + quoteId + "-v2");
        assertThat(objectMapper.readTree(current.get("snapshot").s()).at("/risk/vehicleRef").asText())
                .isEqualTo("vehicle-sedan-2019");
        assertThat(item("IDEMPOTENCY#org-acme#CREATE_VERSION#" + quoteId + "#" + key, "META").get("version").n())
                .isEqualTo("2");
        assertThat(outboxOf(quoteId)).hasSize(4);
        assertThat(outboxOf(quoteId)).filteredOn(item -> item.get("version").n().equals("2"))
                .extracting(item -> item.get("detailType").s())
                .containsExactlyInAnyOrder("StartQuoteRequested", "QuoteAccepted");

        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("currentVersion", equalTo(2))
                .body("supersededAt", notNullValue())
                .body("allowedActions", empty());
        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(200)
                .body("revision", equalTo(2))
                .body("items[0].selectable", equalTo(false))
                .body("items[0].unavailabilityReason", equalTo("VERSION_SUPERSEDED"));
        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/2")
                .then().statusCode(200)
                .body("supersededAt", nullValue())
                .body("allowedActions", contains("CREATE_VERSION"));
    }

    @Test
    void t17_newVersionIsIdempotentAndSerializedByTheExpectedVersion() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        String key = UUID.randomUUID().toString();
        String path = "/v1/quotes/" + quoteId + "/versions";
        postVersion(key, path, QuoteApiContractTest.VERSION_BODY).statusCode(202).body("version", equalTo(2));

        postVersion(key, path, QuoteApiContractTest.VERSION_BODY).statusCode(202).body("version", equalTo(2));
        assertThat(outboxOf(quoteId)).as("the replay adds no execution").hasSize(4);
        postVersion(key, path, QuoteApiContractTest.VERSION_BODY.replace("vehicle-sedan-2019", "vehicle-hatch-2021"))
                .statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
        postVersion(UUID.randomUUID().toString(), path, QuoteApiContractTest.VERSION_BODY)
                .statusCode(409).body("code", equalTo("VERSION_CONFLICT"));
        postVersion(UUID.randomUUID().toString(), path,
                QuoteApiContractTest.VERSION_BODY.replace("\"expectedVersion\":1,", "\"expectedVersion\":2,"))
                .statusCode(202).body("version", equalTo(3));

        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS("QUOTE#" + quoteId), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET selectionId = :selection")
                .expressionAttributeValues(Map.of(":selection", AttributeValue.fromS("sel-it"))));
        postVersion(UUID.randomUUID().toString(), path,
                QuoteApiContractTest.VERSION_BODY.replace("\"expectedVersion\":1,", "\"expectedVersion\":3,"))
                .statusCode(409).body("code", equalTo("QUOTE_ALREADY_SELECTED"));
        assertThat(item("QUOTE#" + quoteId, "META").get("currentVersion").n()).isEqualTo("3");
        assertThat(item("QUOTE#" + quoteId, "V#0004")).isNullOrEmpty();
    }

    @Test
    void newVersionOfAnotherOrganizationIsNotFoundAndAReaderCannotCreateOne() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");

        given().header("Authorization", "Bearer " + globexToken).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON).body(QuoteApiContractTest.VERSION_BODY)
                .when().post("/v1/quotes/" + quoteId + "/versions")
                .then().statusCode(404);
        given().header("Authorization", "Bearer " + readerToken).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON).body(QuoteApiContractTest.VERSION_BODY)
                .when().post("/v1/quotes/" + quoteId + "/versions")
                .then().statusCode(403);
        assertThat(item("QUOTE#" + quoteId, "META").get("currentVersion").n()).isEqualTo("1");
    }

    @Test
    void listingPagesTheOrganizationQuotesByCursorWithTheMetaSummary() {
        Instant start = Instant.now();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId"));
        }
        Instant end = Instant.now();
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS("QUOTE#" + created.get(1)), "sk", AttributeValue.fromS("META")))
                .updateExpression("SET phase = :phase, outcome = :outcome, offerCount = :offers")
                .expressionAttributeValues(Map.of(":phase", AttributeValue.fromS("TERMINAL"),
                        ":outcome", AttributeValue.fromS("COMPLETED"), ":offers", AttributeValue.fromN("2"))));
        String window = "createdFrom=" + start + "&createdTo=" + end;

        io.restassured.response.ExtractableResponse<io.restassured.response.Response> first = given()
                .header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes?limit=2&" + window)
                .then().statusCode(200)
                .body("items.id", contains(created.get(2), created.get(1)))
                .body("items[1].phase", equalTo("TERMINAL"))
                .body("items[1].outcome", equalTo("COMPLETED"))
                .body("items[1].offerCount", equalTo(2))
                .body("items[0].currentVersion", equalTo(1))
                .extract();
        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes?limit=2&" + window + "&cursor=" + first.path("nextCursor"))
                .then().statusCode(200)
                .body("items.id", contains(created.get(0)))
                .body("nextCursor", nullValue());
        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes?phase=TERMINAL&outcome=COMPLETED&" + window)
                .then().statusCode(200)
                .body("items.id", contains(created.get(1)));
        given().header("Authorization", "Bearer " + readerToken)
                .when().get("/v1/quotes?phase=RECEIVED&limit=1&" + window)
                .then().statusCode(200)
                .body("items.id", contains(created.get(2)))
                .body("nextCursor", notNullValue());
        given().header("Authorization", "Bearer " + globexToken)
                .when().get("/v1/quotes?" + window)
                .then().statusCode(200)
                .body("items", empty());
    }

    @Test
    void t28_selectionIsDefinitiveIdempotentAndBlocksNewVersions() throws Exception {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        putOffer(quoteId, "of_a", Instant.now().plusSeconds(600));
        putOffer(quoteId, "of_b", Instant.now().plusSeconds(600));
        String path = "/v1/quotes/" + quoteId + "/versions/1/selection";
        String key = UUID.randomUUID().toString();

        String selectionId = select(writerToken, key, path, "of_a").statusCode(201)
                .body("status", equalTo("INTENT_RECORDED"))
                .extract().path("selectionId");
        assertThat(selectionId).isEqualTo(QuoteKeys.selectionId(quoteId, 1));
        select(writerToken, key, path, "of_a").statusCode(201).body("selectionId", equalTo(selectionId));
        select(writerToken, UUID.randomUUID().toString(), path, "of_a").statusCode(201)
                .body("selectionId", equalTo(selectionId));
        select(writerToken, key, path, "of_b").statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
        select(writerToken, UUID.randomUUID().toString(), path, "of_b").statusCode(409)
                .body("code", equalTo("SELECTION_ALREADY_EXISTS"));
        postVersion(UUID.randomUUID().toString(), "/v1/quotes/" + quoteId + "/versions", QuoteApiContractTest.VERSION_BODY)
                .statusCode(409).body("code", equalTo("QUOTE_ALREADY_SELECTED"));

        Map<String, AttributeValue> meta = item("QUOTE#" + quoteId, "META");
        assertThat(meta.get("selectionId").s()).isEqualTo(selectionId);
        assertThat(meta.get("selectedOfferId").s()).isEqualTo("of_a");
        assertThat(meta.get("currentVersion").n()).isEqualTo("1");
        assertThat(item("QUOTE#" + quoteId, "V#0001#SELECTION").get("selectedBy").s()).isNotBlank();
        assertThat(outboxOf(quoteId)).filteredOn(item -> item.get("detailType").s().equals("OfferSelected"))
                .singleElement().satisfies(outbox -> assertThat(outbox.get("eventId").s())
                        .isEqualTo(Hashes.uuidV5(selectionId).toString()));
        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1")
                .then().statusCode(200)
                .body("selection.offerId", equalTo("of_a"))
                .body("allowedActions", empty());
        given().header("Authorization", "Bearer " + writerToken)
                .when().get("/v1/quotes/" + quoteId + "/versions/1/offers")
                .then().statusCode(200)
                .body("items.unavailabilityReason", contains("SELECTION_ALREADY_EXISTS", "SELECTION_ALREADY_EXISTS"));
    }

    @Test
    void t18_expiredOfferOfferOfAnotherVersionAndQuoteOfAnotherOrganizationAreRejectedWithoutAnIntent() {
        String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
        putOffer(quoteId, "of_expired", Instant.now().minusSeconds(1));
        String path = "/v1/quotes/" + quoteId + "/versions/1/selection";

        select(writerToken, UUID.randomUUID().toString(), path, "of_expired").statusCode(409)
                .body("code", equalTo("OFFER_EXPIRED"));
        select(writerToken, UUID.randomUUID().toString(), path, "of_unknown").statusCode(404);
        given().header("Authorization", "Bearer " + globexToken).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON).body("{\"offerId\":\"of_expired\"}")
                .when().post(path)
                .then().statusCode(404);
        select(readerToken, UUID.randomUUID().toString(), path, "of_expired").statusCode(403);

        assertThat(item("QUOTE#" + quoteId, "META")).doesNotContainKey("selectionId");
        assertThat(item("QUOTE#" + quoteId, "V#0001#SELECTION")).isNullOrEmpty();
        assertThat(outboxOf(quoteId)).noneMatch(item -> item.get("detailType").s().equals("OfferSelected"));

        putOffer(quoteId, "of_valid", Instant.now().plusSeconds(600));
        postVersion(UUID.randomUUID().toString(), "/v1/quotes/" + quoteId + "/versions", QuoteApiContractTest.VERSION_BODY)
                .statusCode(202);
        select(writerToken, UUID.randomUUID().toString(), path, "of_valid").statusCode(409)
                .body("code", equalTo("VERSION_SUPERSEDED"));
        assertThat(item("QUOTE#" + quoteId, "META")).doesNotContainKey("selectionId");
    }

    @Test
    void t29_raceBetweenSelectionAndNewVersionHasExactlyOneWinner() throws Exception {
        for (int round = 0; round < 4; round++) {
            String quoteId = create(writerToken, UUID.randomUUID().toString(), BODY).path("quoteId");
            putOffer(quoteId, "of_a", Instant.now().plusSeconds(600));
            List<Callable<String>> actions = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                actions.add(() -> outcome(select(writerToken, UUID.randomUUID().toString(),
                        "/v1/quotes/" + quoteId + "/versions/1/selection", "of_a")));
                actions.add(() -> outcome(postVersion(UUID.randomUUID().toString(),
                        "/v1/quotes/" + quoteId + "/versions", QuoteApiContractTest.VERSION_BODY)));
            }
            ExecutorService pool = Executors.newFixedThreadPool(actions.size());
            List<String> outcomes = new ArrayList<>();
            try {
                for (Future<String> outcome : pool.invokeAll(actions)) {
                    outcomes.add(outcome.get());
                }
            } finally {
                pool.shutdown();
            }

            Map<String, AttributeValue> meta = item("QUOTE#" + quoteId, "META");
            List<String> selections = List.of(outcomes.get(0), outcomes.get(2), outcomes.get(4));
            List<String> versions = List.of(outcomes.get(1), outcomes.get(3), outcomes.get(5));
            if (meta.containsKey("selectionId")) {
                assertThat(meta.get("currentVersion").n()).isEqualTo("1");
                assertThat(selections).containsOnly("201");
                assertThat(versions).containsOnly("409 QUOTE_ALREADY_SELECTED");
                assertThat(item("QUOTE#" + quoteId, "V#0002")).isNullOrEmpty();
            } else {
                assertThat(meta.get("currentVersion").n()).isEqualTo("2");
                assertThat(selections).containsOnly("409 VERSION_SUPERSEDED");
                assertThat(versions).containsOnlyOnce("202").containsOnly("202", "409 VERSION_CONFLICT");
                assertThat(item("QUOTE#" + quoteId, "V#0001#SELECTION")).isNullOrEmpty();
            }
        }
    }

    /** Status da resposta e, no erro, o {@code code} do contrato: os perdedores da corrida diferem só por ele. */
    private static String outcome(io.restassured.response.ValidatableResponse response) {
        io.restassured.response.Response answer = response.extract().response();
        return answer.statusCode() < 400 ? Integer.toString(answer.statusCode())
                : answer.statusCode() + " " + answer.path("code");
    }

    @Test
    void reconcileOfAKnownQueryIsAuditedQueuedAndScopedToTheOrganization() throws Exception {
        String queryId = "q_" + UUID.randomUUID().toString().replace("-", "");
        String issueId = "is_" + UUID.randomUUID().toString().replace("-", "");
        projectUnknownQuery(issueId, queryId, "org-acme");
        String path = "/v1/operations/queries/" + queryId + "/reconcile";
        String body = "{\"reason\":\"Consulta UNKNOWN; conferir com o fornecedor.\"}";
        String key = UUID.randomUUID().toString();

        given().header("Authorization", "Bearer " + operatorToken)
                .when().get("/v1/operations/issues?type=UNKNOWN_QUERY&status=OPEN&limit=100")
                .then().statusCode(200)
                .body("items.find { it.id == '" + issueId + "' }.allowedActions", contains("RECONCILE"));
        String operationId = given().header("Authorization", "Bearer " + operatorToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then().statusCode(202)
                .body("status", equalTo("QUEUED"))
                .extract().path("operationId");
        given().header("Authorization", "Bearer " + operatorToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then().statusCode(202)
                .body("operationId", equalTo(operationId));

        Map<String, AttributeValue> operation = item("OPERATION#" + operationId, "META");
        assertThat(operation.get("status").s()).isEqualTo("QUEUED");
        assertThat(operation.get("organizationId").s()).isEqualTo("org-acme");
        assertThat(operation.get("reason").s()).isEqualTo("Consulta UNKNOWN; conferir com o fornecedor.");
        assertThat(operation.get("requestedBy").s()).isNotBlank();
        List<Map<String, AttributeValue>> requests = dynamoDb.scan(builder -> builder.tableName(FlociResource.TABLE)
                .filterExpression("#type = :outbox AND operationId = :operationId")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":outbox", AttributeValue.fromS("OUTBOX"),
                        ":operationId", AttributeValue.fromS(operationId)))).items();
        assertThat(requests).singleElement().satisfies(outbox -> {
            assertThat(outbox.get("detailType").s()).isEqualTo("QueryReconcileRequested");
            assertThat(outbox.get("gsi2pk").s()).isEqualTo("OUTBOX_PENDING");
            assertThat(objectMapper.readTree(outbox.get("detail").s()).get("queryId").asText()).isEqualTo(queryId);
        });
        given().header("Authorization", "Bearer " + operatorToken)
                .when().get("/v1/operations/" + operationId)
                .then().statusCode(200)
                .body("status", equalTo("QUEUED"))
                .body("queryId", equalTo(queryId));

        given().header("Authorization", "Bearer " + globexOperatorToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then().statusCode(404);
        given().header("Authorization", "Bearer " + globexOperatorToken)
                .when().get("/v1/operations/" + operationId)
                .then().statusCode(404);
        given().header("Authorization", "Bearer " + globexOperatorToken)
                .when().get("/v1/operations/issues?limit=100")
                .then().statusCode(200)
                .body("items.id", not(hasItem(issueId)));
        given().header("Authorization", "Bearer " + writerToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then().statusCode(403);
        given().header("Authorization", "Bearer " + operatorToken).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON).body(body)
                .when().post("/v1/operations/queries/q_unknown/reconcile")
                .then().statusCode(404);
    }

    /** {@code ISSUE#} e {@code QUERYREF#} como o {@code issue-projector} os grava a partir do evento da provider. */
    private void projectUnknownQuery(String issueId, String queryId, String organizationId) {
        String now = Instant.now().toString();
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(Map.ofEntries(
                Map.entry("pk", AttributeValue.fromS("ISSUE#" + issueId)), Map.entry("sk", AttributeValue.fromS("META")),
                Map.entry("type", AttributeValue.fromS("ISSUE")), Map.entry("issueId", AttributeValue.fromS(issueId)),
                Map.entry("issueType", AttributeValue.fromS("UNKNOWN_QUERY")),
                Map.entry("status", AttributeValue.fromS("OPEN")), Map.entry("source", AttributeValue.fromS("provider")),
                Map.entry("organizationId", AttributeValue.fromS(organizationId)),
                Map.entry("queryId", AttributeValue.fromS(queryId)), Map.entry("firstSeenAt", AttributeValue.fromS(now)),
                Map.entry("lastSeenAt", AttributeValue.fromS(now)),
                Map.entry("safeSummary", AttributeValue.fromS("Consulta sem resposta do fornecedor")),
                Map.entry("reconcilable", AttributeValue.fromBool(true)), Map.entry("revision", AttributeValue.fromN("1")),
                Map.entry("gsi1pk", AttributeValue.fromS("ISSUES#" + organizationId)),
                Map.entry("gsi1sk", AttributeValue.fromS(now + "#" + issueId)))));
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(Map.of(
                "pk", AttributeValue.fromS("QUERYREF#" + queryId), "sk", AttributeValue.fromS("META"),
                "type", AttributeValue.fromS("QUERYREF"), "queryId", AttributeValue.fromS(queryId),
                "organizationId", AttributeValue.fromS(organizationId))));
    }

    private io.restassured.response.ValidatableResponse select(String token, String key, String path, String offerId) {
        return given().header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body("{\"offerId\":\"" + offerId + "\"}")
                .when().post(path)
                .then();
    }

    private io.restassured.response.ValidatableResponse postVersion(String key, String path, String body) {
        return given().header("Authorization", "Bearer " + writerToken).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then();
    }

    private io.restassured.response.ExtractableResponse<io.restassured.response.Response> create(String token,
            String key, String body) {
        return given().header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post("/v1/quotes")
                .then().statusCode(202)
                .body("version", equalTo(1))
                .extract();
    }

    private void putOffer(String quoteId, String offerId, Instant validUntil) {
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(Map.ofEntries(
                Map.entry("pk", AttributeValue.fromS("QUOTE#" + quoteId)),
                Map.entry("sk", AttributeValue.fromS("V#0001#OFFER#" + offerId)),
                Map.entry("type", AttributeValue.fromS("OFFER")),
                Map.entry("offerId", AttributeValue.fromS(offerId)),
                Map.entry("insurerId", AttributeValue.fromS("alpha")),
                Map.entry("insurerName", AttributeValue.fromS("Alpha Seguros")),
                Map.entry("premiumTotal", AttributeValue.fromS("1999.90")),
                Map.entry("currency", AttributeValue.fromS("BRL")),
                Map.entry("installments", AttributeValue.fromS("[{\"count\":1,\"amount\":\"1999.90\"}]")),
                Map.entry("coverages", AttributeValue.fromS("[]")),
                Map.entry("deductibles", AttributeValue.fromS("[]")),
                Map.entry("validUntil", AttributeValue.fromS(validUntil.toString())),
                Map.entry("createdAt", AttributeValue.fromS(Instant.now().toString())))));
    }

    /** Fase e resultado da versão como os passos da máquina gravam, com a {@code revision} incrementada. */
    private void setVersion(String quoteId, String phase, String outcome) {
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":phase", AttributeValue.fromS(phase),
                ":one", AttributeValue.fromN("1"), ":now", AttributeValue.fromS(Instant.now().toString())));
        String expression = "SET phase = :phase, revision = revision + :one, updatedAt = :now";
        if (outcome != null) {
            values.put(":outcome", AttributeValue.fromS(outcome));
            expression += ", outcome = :outcome, missingFields = :missing";
            values.put(":missing", AttributeValue.fromS("[]"));
        }
        String update = expression;
        dynamoDb.updateItem(builder -> builder.tableName(FlociResource.TABLE)
                .key(Map.of("pk", AttributeValue.fromS("QUOTE#" + quoteId), "sk", AttributeValue.fromS("V#0001")))
                .updateExpression(update).expressionAttributeValues(values));
    }

    /** {@code RESULT} com os atributos que a seleção, a {@code dispatch} e o {@code resume} gravam. */
    private void putResult(String quoteId, String insurerId, String insurerName, String status, String reasonCode) {
        Map<String, AttributeValue> result = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUOTE#" + quoteId),
                "sk", AttributeValue.fromS("V#0001#RESULT#" + insurerId),
                "type", AttributeValue.fromS("RESULT"),
                "insurerId", AttributeValue.fromS(insurerId),
                "insurerName", AttributeValue.fromS(insurerName),
                "status", AttributeValue.fromS(status),
                "missingFields", AttributeValue.fromS("[]"),
                "revision", AttributeValue.fromN("PROCESSING".equals(status) ? "2" : "3"),
                "updatedAt", AttributeValue.fromS(Instant.now().toString())));
        if (reasonCode != null) {
            result.put("reasonCode", AttributeValue.fromS(reasonCode));
        }
        dynamoDb.putItem(builder -> builder.tableName(FlociResource.TABLE).item(result));
    }

    private Map<String, AttributeValue> item(String pk, String sk) {
        return dynamoDb.getItem(builder -> builder.tableName(FlociResource.TABLE).consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk)))).item();
    }

    private List<Map<String, AttributeValue>> outboxOf(String quoteId) {
        return dynamoDb.scan(builder -> builder.tableName(FlociResource.TABLE)
                .filterExpression("#type = :outbox AND quoteId = :quoteId")
                .expressionAttributeNames(Map.of("#type", "type"))
                .expressionAttributeValues(Map.of(":outbox", AttributeValue.fromS("OUTBOX"),
                        ":quoteId", AttributeValue.fromS(quoteId)))).items();
    }
}

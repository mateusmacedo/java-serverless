package com.mmda.serverless.quote.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

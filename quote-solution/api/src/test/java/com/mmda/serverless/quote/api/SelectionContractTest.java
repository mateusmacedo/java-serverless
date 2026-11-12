package com.mmda.serverless.quote.api;

import static com.mmda.serverless.quote.api.QuoteApiContractTest.CONTRACT;
import static com.mmda.serverless.quote.api.QuoteApiContractTest.RESPONSES_ONLY;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mmda.serverless.quote.domain.CanonicalJson;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.QuoteKeys;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * {@code POST /v1/quotes/{id}/versions/{v}/selection} contra {@code contracts/openapi.yaml}, com a tabela simulada:
 * a transação, as respostas de T18, T28 e T29 do lado da API e as ações da versão depois da seleção.
 */
@QuarkusTest
@WithTestResource(JwtKeyResource.class)
class SelectionContractTest {

    private static final String PATH = "/v1/quotes/q-1/versions/1/selection";
    private static final String BODY = "{\"offerId\":\"of_1\"}";

    private final String selector = TestTokens.bearer(TestTokens.access("ana", "org_org-acme", "perm_quotes_read",
            "perm_quotes_write", "perm_offers_select"));

    @InjectMock
    DynamoDbClient dynamoDb;

    private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    @BeforeEach
    void table() {
        items.clear();
        meta(Map.of());
        offer("of_1", Instant.now().plusSeconds(1800));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            return GetItemResponse.builder()
                    .item(items.get(request.key().get("pk").s() + "|" + request.key().get("sk").s())).build();
        });
    }

    @Test
    void selectionDuringQuotingRecordsTheIntentAndTheEventInOneTransaction() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        select("select-1", BODY).statusCode(201)
                .body("selectionId", equalTo(QuoteKeys.selectionId("q-1", 1)))
                .body("offerId", equalTo("of_1"))
                .body("status", equalTo("INTENT_RECORDED"));

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        List<TransactWriteItem> writes = captor.getValue().transactItems();
        assertThat(writes).hasSize(5);
        assertThat(writes.get(0).put().item().get("pk").s()).isEqualTo("IDEMPOTENCY#org-acme#SELECT_OFFER#q-1#select-1");
        assertThat(writes.get(1).put().item().get("sk").s()).isEqualTo("V#0001#SELECTION");
        assertThat(writes.get(1).put().item().get("selectedBy").s()).isEqualTo("ana");
        assertThat(writes.get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(pk)");
        assertThat(writes.get(2).update().conditionExpression())
                .isEqualTo("currentVersion = :v AND attribute_not_exists(selectionId) AND organizationId = :org");
        assertThat(writes.get(3).conditionCheck().key().get("sk").s()).isEqualTo("V#0001#OFFER#of_1");
        assertThat(writes.get(3).conditionCheck().conditionExpression()).contains("validUntil > :now");
        assertThat(writes.get(4).put().item().get("detailType").s()).isEqualTo("OfferSelected");
        assertThat(writes.get(4).put().item().get("eventId").s())
                .isEqualTo(Hashes.uuidV5(QuoteKeys.selectionId("q-1", 1)).toString());
    }

    @Test
    void t28_sameOfferReturnsTheExistingSelectionEvenWithAnotherKey() {
        selected("of_1");

        select("select-2", BODY).statusCode(201)
                .body("selectionId", equalTo("sel_existing"))
                .body("selectedAt", equalTo("2026-11-12T10:00:06Z"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void t28_anotherOfferAfterTheSelectionIsSelectionAlreadyExists() {
        selected("of_other");

        select("select-3", BODY).statusCode(409).body("code", equalTo("SELECTION_ALREADY_EXISTS"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void t18_expiredOfferIsRejectedWithoutRecordingAnIntent() {
        offer("of_1", Instant.now().minusSeconds(1));

        select("select-4", BODY).statusCode(409).body("code", equalTo("OFFER_EXPIRED"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void t18_offerOutsideTheVersionAndQuoteOfAnotherOrganizationAreNotFound() {
        String globex = TestTokens.bearer(TestTokens.access("carla", "org_org-globex", "perm_offers_select"));

        select("select-5", "{\"offerId\":\"of_globex\"}").statusCode(404).body("code", equalTo("NOT_FOUND"));
        given().filter(CONTRACT).header("Authorization", globex).header("Idempotency-Key", "select-6")
                .contentType(ContentType.JSON).body(BODY)
                .when().post(PATH)
                .then().statusCode(404);
        select("select-7", BODY, "/v1/quotes/q-1/versions/2/selection").statusCode(404);
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void selectionOnASupersededVersionIsAConflict() {
        meta(Map.of("currentVersion", AttributeValue.fromN("2")));

        select("select-8", BODY).statusCode(409).body("code", equalTo("VERSION_SUPERSEDED"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void t29_newVersionThatWinsTheRaceTurnsTheSelectionIntoVersionSuperseded() {
        AtomicInteger metaReads = new AtomicInteger();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            String key = request.key().get("pk").s() + "|" + request.key().get("sk").s();
            if ("QUOTE#q-1|META".equals(key) && metaReads.incrementAndGet() > 1) {
                Map<String, AttributeValue> moved = new HashMap<>(items.get(key));
                moved.put("currentVersion", AttributeValue.fromN("2"));
                return GetItemResponse.builder().item(moved).build();
            }
            return GetItemResponse.builder().item(items.get(key)).build();
        });
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(canceledAt(2));

        select("select-9", BODY).statusCode(409).body("code", equalTo("VERSION_SUPERSEDED"));
    }

    @Test
    void t29_concurrentSelectionThatWinsDecidesTheAnswerOfTheOther() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(invocation -> {
            selected("of_other");
            throw canceledAt(1);
        });

        select("select-10", BODY).statusCode(409).body("code", equalTo("SELECTION_ALREADY_EXISTS"));
    }

    @Test
    void offerExpiringBetweenTheReadAndTheCommitIsOfferExpired() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(canceledAt(3));

        select("select-11", BODY).statusCode(409).body("code", equalTo("OFFER_EXPIRED"));
    }

    @Test
    void resubmissionWithTheSameKeyIsAReplayAndAnotherBodyIsAConflict() {
        items.put("IDEMPOTENCY#org-acme#SELECT_OFFER#q-1#select-12|META", Map.of(
                "requestHash", AttributeValue.fromS(Hashes.sha256Hex(CanonicalJson.canonicalize(BODY))),
                "selectionId", AttributeValue.fromS("sel_recorded"), "offerId", AttributeValue.fromS("of_1"),
                "selectedAt", AttributeValue.fromS("2026-11-12T10:00:06Z")));

        select("select-12", "{ \"offerId\" : \"of_1\" }").statusCode(201).body("selectionId", equalTo("sel_recorded"));
        select("select-12", "{\"offerId\":\"of_2\"}").statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void failureBeforeTheDurableSelectionIsUnavailable() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(SdkClientException.create("read timed out"));

        select("select-13", BODY).statusCode(503).body("code", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    void t30_selectionWithoutOffersSelectIsForbidden() {
        String reader = TestTokens.bearer(TestTokens.access("rui", "org_org-acme", "perm_quotes_read",
                "perm_quotes_write"));

        given().filter(CONTRACT).header("Authorization", reader).header("Idempotency-Key", "select-14")
                .contentType(ContentType.JSON).body(BODY)
                .when().post(PATH)
                .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void invalidSelectionRequestsAreValidationErrors() {
        given().filter(RESPONSES_ONLY).header("Authorization", selector).contentType(ContentType.JSON).body(BODY)
                .when().post(PATH)
                .then().statusCode(400).body("fieldErrors.path", contains("Idempotency-Key"));
        given().filter(RESPONSES_ONLY).header("Authorization", selector).header("Idempotency-Key", "select-15")
                .contentType(ContentType.JSON).body("{\"offerId\":\"\",\"extra\":1}")
                .when().post(PATH)
                .then().statusCode(400).body("fieldErrors.path", contains("extra", "offerId"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void t29_afterTheSelectionALaterNeedsInformationOffersNoAction() {
        selected("of_1");
        Map<String, AttributeValue> version = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUOTE#q-1"), "sk", AttributeValue.fromS("V#0001"),
                "snapshot", AttributeValue.fromS(QuoteApiContractTest.SNAPSHOT),
                "phase", AttributeValue.fromS("TERMINAL"), "outcome", AttributeValue.fromS("PARTIAL"),
                "missingFields", AttributeValue.fromS("[]"), "revision", AttributeValue.fromN("6"),
                "updatedAt", AttributeValue.fromS(Instant.now().toString())));
        Map<String, AttributeValue> beta = Map.of(
                "sk", AttributeValue.fromS("V#0001#RESULT#beta"), "insurerId", AttributeValue.fromS("beta"),
                "insurerName", AttributeValue.fromS("Beta Seguros"), "status", AttributeValue.fromS("NEEDS_INFORMATION"),
                "reasonCode", AttributeValue.fromS("MISSING_DATA"),
                "missingFields", AttributeValue.fromS(
                        "[{\"path\":\"risk.principalDriver.licenseDate\",\"reasonCode\":\"MISSING_LICENSE_DATE\"}]"),
                "revision", AttributeValue.fromN("3"), "updatedAt", AttributeValue.fromS(Instant.now().toString()));
        Map<String, AttributeValue> selection = items.get("QUOTE#q-1|V#0001#SELECTION");
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(version, beta, items.get("QUOTE#q-1|V#0001#OFFER#of_1"), selection).build());

        given().filter(CONTRACT).header("Authorization", selector)
                .when().get("/v1/quotes/q-1/versions/1")
                .then().statusCode(200)
                .body("outcome", equalTo("PARTIAL"))
                .body("insurerResults[0].missingFields[0].reasonCode", equalTo("MISSING_LICENSE_DATE"))
                .body("selection.status", equalTo("INTENT_RECORDED"))
                .body("allowedActions", empty());
        given().filter(CONTRACT).header("Authorization", selector)
                .when().get("/v1/quotes/q-1/versions/1/offers")
                .then().statusCode(200)
                .body("items[0].unavailabilityReason", equalTo("SELECTION_ALREADY_EXISTS"));
    }

    private ValidatableResponse select(String key, String body) {
        return select(key, body, PATH);
    }

    private ValidatableResponse select(String key, String body, String path) {
        return given().filter(CONTRACT).header("Authorization", selector).header("Idempotency-Key", key)
                .contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then();
    }

    private void meta(Map<String, AttributeValue> changes) {
        Map<String, AttributeValue> meta = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUOTE#q-1"), "sk", AttributeValue.fromS("META"),
                "organizationId", AttributeValue.fromS("org-acme"), "currentVersion", AttributeValue.fromN("1")));
        meta.putAll(changes);
        items.put("QUOTE#q-1|META", meta);
    }

    private void selected(String offerId) {
        meta(Map.of("selectionId", AttributeValue.fromS("sel_existing"), "selectedVersion", AttributeValue.fromN("1"),
                "selectedOfferId", AttributeValue.fromS(offerId)));
        items.put("QUOTE#q-1|V#0001#SELECTION", Map.of(
                "sk", AttributeValue.fromS("V#0001#SELECTION"), "selectionId", AttributeValue.fromS("sel_existing"),
                "offerId", AttributeValue.fromS(offerId), "selectedAt", AttributeValue.fromS("2026-11-12T10:00:06Z")));
    }

    private void offer(String offerId, Instant validUntil) {
        items.put("QUOTE#q-1|V#0001#OFFER#" + offerId, Map.ofEntries(
                Map.entry("pk", AttributeValue.fromS("QUOTE#q-1")),
                Map.entry("sk", AttributeValue.fromS("V#0001#OFFER#" + offerId)),
                Map.entry("offerId", AttributeValue.fromS(offerId)), Map.entry("insurerId", AttributeValue.fromS("alpha")),
                Map.entry("insurerName", AttributeValue.fromS("Alpha Seguros")),
                Map.entry("premiumTotal", AttributeValue.fromS("2450.90")), Map.entry("currency", AttributeValue.fromS("BRL")),
                Map.entry("installments", AttributeValue.fromS("[{\"count\":1,\"amount\":\"2450.90\"}]")),
                Map.entry("coverages", AttributeValue.fromS("[]")), Map.entry("deductibles", AttributeValue.fromS("[]")),
                Map.entry("validUntil", AttributeValue.fromS(validUntil.toString())),
                Map.entry("createdAt", AttributeValue.fromS(Instant.now().toString()))));
    }

    /** Transação da seleção cancelada pela condição da ação informada (1 seleção, 2 META, 3 oferta). */
    private static TransactionCanceledException canceledAt(int action) {
        CancellationReason[] reasons = new CancellationReason[5];
        for (int i = 0; i < reasons.length; i++) {
            reasons[i] = CancellationReason.builder().code(i == action ? "ConditionalCheckFailed" : "None").build();
        }
        return TransactionCanceledException.builder().message("canceled").cancellationReasons(reasons).build();
    }
}

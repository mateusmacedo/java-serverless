package com.mmda.serverless.quote.api;

import static com.mmda.serverless.quote.api.QuoteApiContractTest.CONTRACT;
import static com.mmda.serverless.quote.api.QuoteApiContractTest.RESPONSES_ONLY;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.CanonicalJson;
import com.mmda.serverless.quote.domain.Hashes;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

/** {@code /v1/operations} contra {@code contracts/openapi.yaml}, com a tabela simulada (§8 e §11 do desenho, T30). */
@QuarkusTest
@WithTestResource(JwtKeyResource.class)
class OperationsContractTest {

    private static final String QUERY = "q_099d4987d3e2f2e6656e9285ac76a83e";
    private static final String RECONCILE = "/v1/operations/queries/" + QUERY + "/reconcile";
    private static final String REASON = "{\"reason\":\"Consulta UNKNOWN há mais de 10 minutos.\"}";

    private final String operator = TestTokens.bearer(TestTokens.access("olga", "org_org-acme",
            "perm_operations_read", "perm_operations_reconcile"));
    private final String viewer = TestTokens.bearer(TestTokens.access("vera", "org_org-acme", "perm_operations_read"));

    @InjectMock
    DynamoDbClient dynamoDb;

    private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    @BeforeEach
    void table() {
        items.clear();
        items.put("QUERYREF#" + QUERY + "|META", Map.of("queryId", AttributeValue.fromS(QUERY),
                "organizationId", AttributeValue.fromS("org-acme")));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            DynamoDbLimits.checkKey(request.key());
            return GetItemResponse.builder()
                    .item(items.get(request.key().get("pk").s() + "|" + request.key().get("sk").s())).build();
        });
    }

    @Test
    void issuesFollowTheContractAndOfferReconcileOnlyWhereItIsAllowed() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(
                issue("is_open", "UNKNOWN_QUERY", "OPEN", QUERY, true),
                issue("is_dead", "DEAD_LETTER", "OPEN", "q_dead", false),
                issue("is_done", "UNKNOWN_QUERY", "RESOLVED", "q_done", true),
                issue("iss_stuck", "STUCK_VERSION", "OPEN", null, false)).build());

        given().filter(CONTRACT).header("Authorization", operator)
                .when().get("/v1/operations/issues")
                .then().statusCode(200)
                .body("items.id", contains("is_open", "is_dead", "is_done", "iss_stuck"))
                .body("items.type", contains("UNKNOWN_QUERY", "DEAD_LETTER", "UNKNOWN_QUERY", "STUCK_VERSION"))
                .body("items[0].allowedActions", contains("RECONCILE"))
                .body("items[1].allowedActions", empty())
                .body("items[2].allowedActions", empty())
                .body("items[3].queryId", nullValue())
                .body("nextCursor", nullValue());
        given().filter(CONTRACT).header("Authorization", viewer)
                .when().get("/v1/operations/issues")
                .then().statusCode(200)
                .body("items[0].allowedActions", empty());
    }

    @Test
    void issueFiltersGoToTheOrganizationIndex() {
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        when(dynamoDb.query(captor.capture())).thenReturn(QueryResponse.builder().items(List.of()).build());

        given().filter(CONTRACT).header("Authorization", viewer)
                .when().get("/v1/operations/issues?type=UNKNOWN_QUERY&status=OPEN&limit=5")
                .then().statusCode(200)
                .body("items", empty());

        QueryRequest request = captor.getValue();
        assertThat(request.indexName()).isEqualTo("gsi1");
        assertThat(request.expressionAttributeValues().get(":gpk").s()).isEqualTo("ISSUES#org-acme");
        assertThat(request.filterExpression()).isEqualTo("issueType = :issueType AND #status = :status");
        given().filter(RESPONSES_ONLY).header("Authorization", viewer)
                .when().get("/v1/operations/issues?type=SOMETHING&status=CLOSED")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("type", "status"));
    }

    @Test
    void reconcileOfAKnownQueryRecordsTheAuditAndTheRequestInOneTransaction() throws Exception {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        String operationId = given().filter(CONTRACT).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-1").contentType(ContentType.JSON).body(REASON)
                .when().post(RECONCILE)
                .then().statusCode(202)
                .header("Location", matchesPattern("^/v1/operations/op_[0-9a-f]{32}$"))
                .body("status", equalTo("QUEUED"))
                .extract().path("operationId");

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        List<TransactWriteItem> writes = captor.getValue().transactItems();
        assertThat(writes).hasSize(3).allSatisfy(write ->
                assertThat(write.put().conditionExpression()).isEqualTo("attribute_not_exists(pk)"));
        assertThat(writes.get(0).put().item().get("pk").s())
                .isEqualTo("IDEMPOTENCY#org-acme#RECONCILE#" + QUERY + "#reconcile-1");
        Map<String, AttributeValue> operation = writes.get(1).put().item();
        assertThat(operation.get("pk").s()).isEqualTo("OPERATION#" + operationId);
        assertThat(operation.get("status").s()).isEqualTo("QUEUED");
        assertThat(operation.get("reason").s()).isEqualTo("Consulta UNKNOWN há mais de 10 minutos.");
        assertThat(operation.get("requestedBy").s()).isEqualTo("olga");
        assertThat(operation.get("revision").n()).isEqualTo("0");
        Map<String, AttributeValue> outbox = writes.get(2).put().item();
        assertThat(outbox.get("detailType").s()).isEqualTo("QueryReconcileRequested");
        JsonNode detail = new ObjectMapper().readTree(outbox.get("detail").s());
        assertThat(detail.get("operationId").asText()).isEqualTo(operationId);
        assertThat(detail.get("organizationId").asText()).isEqualTo("org-acme");
        assertThat(detail.get("queryId").asText()).isEqualTo(QUERY);
        assertThat(detail.get("requestedBy").asText()).isEqualTo("olga");
    }

    @Test
    void reconcileOfAnUnknownQueryOrOfAnotherOrganizationIsNotFound() {
        items.put("QUERYREF#q_globex|META", Map.of("organizationId", AttributeValue.fromS("org-globex")));

        for (String queryId : List.of("q_unknown", "q_globex")) {
            given().filter(CONTRACT).header("Authorization", operator)
                    .header("Idempotency-Key", "reconcile-2").contentType(ContentType.JSON).body(REASON)
                    .when().post("/v1/operations/queries/" + queryId + "/reconcile")
                    .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        }
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void t30_reconcileWithoutOperationsReconcileIsForbidden() {
        String writer = TestTokens.bearer(TestTokens.access("ana", "org_org-acme", "perm_quotes_read",
                "perm_quotes_write", "perm_offers_select"));

        for (String token : List.of(viewer, writer)) {
            given().filter(CONTRACT).header("Authorization", token)
                    .header("Idempotency-Key", "reconcile-3").contentType(ContentType.JSON).body(REASON)
                    .when().post(RECONCILE)
                    .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
        }
        given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/operations/issues")
                .then().statusCode(403);
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void reconcileResubmittedWithTheSameKeyIsAReplayOfTheSameOperation() {
        items.put("IDEMPOTENCY#org-acme#RECONCILE#" + QUERY + "#reconcile-4|META", Map.of(
                "requestHash", AttributeValue.fromS(Hashes.sha256Hex(CanonicalJson.canonicalize(REASON))),
                "operationId", AttributeValue.fromS("op_recorded")));
        items.put("OPERATION#op_recorded|META", operation("op_recorded", "org-acme", "SUCCEEDED"));

        given().filter(CONTRACT).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-4").contentType(ContentType.JSON).body(REASON)
                .when().post(RECONCILE)
                .then().statusCode(202)
                .body("operationId", equalTo("op_recorded"))
                .body("status", equalTo("QUEUED"))
                .body("statusUrl", equalTo("/v1/operations/op_recorded"));
        given().filter(CONTRACT).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-4").contentType(ContentType.JSON).body("{\"reason\":\"outro\"}")
                .when().post(RECONCILE)
                .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_CONFLICT"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void idsAndCursorLongerThanAKeyAreNotFoundOrInvalidWithoutAnInternalError() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenAnswer(invocation -> DynamoDbLimits.query(invocation.getArgument(0), List.of()));

        given().filter(RESPONSES_ONLY).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-6").contentType(ContentType.JSON).body(REASON)
                .when().post("/v1/operations/queries/q_" + "9".repeat(3000) + "/reconcile")
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        given().filter(RESPONSES_ONLY).header("Authorization", operator)
                .when().get("/v1/operations/op_" + "9".repeat(3000))
                .then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        given().filter(RESPONSES_ONLY).header("Authorization", operator)
                .when().get("/v1/operations/issues?cursor="
                        + QuoteViews.encodeCursor("2026-11-12T04:12:57.575Z".repeat(43) + "#is_open"))
                .then().statusCode(400).body("fieldErrors.path", contains("cursor"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    /** O {@code maxLength} 500 do motivo conta caracteres (code points), como o JSON Schema do contrato. */
    @Test
    void reasonLengthCountsCharactersLikeTheContract() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());
        String emojis = "Consulta parada " + "🔎".repeat(250);

        given().filter(RESPONSES_ONLY).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-7").contentType(ContentType.JSON)
                .body("{\"reason\":\"" + emojis + "\"}")
                .when().post(RECONCILE)
                .then().statusCode(202);
        given().filter(RESPONSES_ONLY).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-8").contentType(ContentType.JSON)
                .body("{\"reason\":\"" + "🔎".repeat(501) + "\"}")
                .when().post(RECONCILE)
                .then().statusCode(400).body("fieldErrors.path", contains("reason"));
    }

    @Test
    void reconcileWithoutAReasonIsAValidationError() {
        given().filter(RESPONSES_ONLY).header("Authorization", operator)
                .header("Idempotency-Key", "reconcile-5").contentType(ContentType.JSON).body("{\"reason\":\"  \"}")
                .when().post(RECONCILE)
                .then().statusCode(400).body("fieldErrors.path", contains("reason"));
    }

    @Test
    void operationFollowsTheContractAndIsScopedToTheOrganization() {
        items.put("OPERATION#op_acme|META", operation("op_acme", "org-acme", "FAILED"));
        String globex = TestTokens.bearer(TestTokens.access("gil", "org_org-globex", "perm_operations_read"));

        given().filter(CONTRACT).header("Authorization", viewer)
                .when().get("/v1/operations/op_acme")
                .then().statusCode(200)
                .body("status", equalTo("FAILED"))
                .body("queryId", equalTo(QUERY));
        given().filter(CONTRACT).header("Authorization", globex)
                .when().get("/v1/operations/op_acme")
                .then().statusCode(404);
        given().filter(CONTRACT).header("Authorization", viewer)
                .when().get("/v1/operations/op_missing")
                .then().statusCode(404);
    }

    private static Map<String, AttributeValue> issue(String issueId, String type, String status, String queryId,
            boolean reconcilable) {
        Map<String, AttributeValue> issue = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("ISSUE#" + issueId), "sk", AttributeValue.fromS("META"),
                "issueId", AttributeValue.fromS(issueId), "issueType", AttributeValue.fromS(type),
                "status", AttributeValue.fromS(status), "firstSeenAt", AttributeValue.fromS("2026-11-12T04:12:57.575Z"),
                "lastSeenAt", AttributeValue.fromS("2026-11-12T04:12:57.575Z"),
                "safeSummary", AttributeValue.fromS("Resumo seguro"), "reconcilable", AttributeValue.fromBool(reconcilable),
                "gsi1sk", AttributeValue.fromS("2026-11-12T04:12:57.575Z#" + issueId)));
        if (queryId != null) {
            issue.put("queryId", AttributeValue.fromS(queryId));
        }
        return issue;
    }

    private static Map<String, AttributeValue> operation(String operationId, String organizationId, String status) {
        return Map.of("operationId", AttributeValue.fromS(operationId), "organizationId", AttributeValue.fromS(organizationId),
                "queryId", AttributeValue.fromS(QUERY), "status", AttributeValue.fromS(status),
                "safeSummary", AttributeValue.fromS("Resumo seguro"),
                "updatedAt", AttributeValue.fromS("2026-11-12T04:13:18.155Z"));
    }
}

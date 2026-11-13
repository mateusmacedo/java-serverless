package com.mmda.serverless.quote.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Confere requisições e respostas contra {@code contracts/openapi.yaml}, a API de produto do contrato
 * compartilhado. O base path é zerado porque o servidor do contrato inclui o prefixo do API Gateway.
 */
@QuarkusTest
@WithTestResource(JwtKeyResource.class)
class QuoteApiContractTest {

    private static final String SPECIFICATION =
            Path.of("../../contracts/openapi.yaml").toAbsolutePath().toUri().toString();

    private static final OpenApiValidationFilter CONTRACT = new OpenApiValidationFilter(OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .build());

    // WHY: nos casos de erro a requisição é inválida de propósito; só a resposta é conferida
    private static final OpenApiValidationFilter RESPONSES_ONLY = new OpenApiValidationFilter(OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build())
            .build());

    static final String CREATE_BODY = """
            {"product":"AUTO","applicantRef":"customer-acme-ana",
             "risk":{"vehicleRef":"vehicle-hatch-2021","usage":"PERSONAL","principalDriverRef":"driver-acme-ana"},
             "coverageProfile":"COMPREHENSIVE","desiredStartDate":"2026-12-01"}
            """;

    static final String SNAPSHOT = """
            {"product":"AUTO","applicantRef":"customer-acme-ana","applicant":{"id":"customer-acme-ana","label":"Ana"},
             "risk":{"vehicleRef":"vehicle-hatch-2021","usage":"PERSONAL","principalDriverRef":"driver-acme-ana",
               "vehicle":{"id":"vehicle-hatch-2021","label":"Hatch","make":"Fiat","model":"Argo","year":2021,"salvage":false},
               "principalDriver":{"id":"driver-acme-ana","label":"Ana","licenseDate":"2012-03-15"}},
             "coverageProfile":"COMPREHENSIVE",
             "coverage":{"code":"COMPREHENSIVE","label":"Completa","summary":"Tudo",
               "coverages":[{"code":"COLLISION","label":"Colisão","insuredAmount":"90000.00"}]},
             "desiredStartDate":"2026-12-01"}
            """;

    private final String writer = TestTokens.bearer(TestTokens.access("ana", "org_org-acme", "perm_quotes_read",
            "perm_quotes_write", "perm_offers_select"));
    private final String reader = TestTokens.bearer(TestTokens.access("rui", "org_org-acme", "perm_quotes_read"));

    @InjectMock
    DynamoDbClient dynamoDb;

    @Test
    void acceptedCreationFollowsTheContract() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-1")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(202)
                .header("Location", matchesPattern("^/v1/quotes/[0-9a-f-]{36}/versions/1$"))
                .header(RequestCorrelation.HEADER, notNullValue())
                .body("phase", equalTo("RECEIVED"))
                .body("version", equalTo(1));
    }

    @Test
    void t03_conflictingResubmissionFollowsTheContract() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(
                TransactionCanceledException.builder().message("canceled").cancellationReasons(
                        CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("None").build()).build());
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(Map.of(
                "requestHash", AttributeValue.fromS("other-hash"),
                "quoteId", AttributeValue.fromS("q-existing"),
                "version", AttributeValue.fromN("1"))).build());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-2")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_CONFLICT"))
                .body("correlationId", notNullValue());
    }

    @Test
    void throttledCreationWithoutDurableAcceptanceIsUnavailable() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(canceled("ThrottlingError"));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-8")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(503)
                .body("code", equalTo("SERVICE_UNAVAILABLE"))
                .body("correlationId", notNullValue());
    }

    @Test
    void transactionConflictIsRetriedAndThenUnavailable() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(canceled("TransactionConflict"));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-9")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(503)
                .body("code", equalTo("SERVICE_UNAVAILABLE"));
        verify(dynamoDb, times(4)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void conflictWithAnIdenticalRequestAcceptedMeanwhileIsAReplay() {
        AtomicReference<String> requestHash = new AtomicReference<>();
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(invocation -> {
            TransactWriteItemsRequest request = invocation.getArgument(0);
            requestHash.set(request.transactItems().getFirst().put().item().get("requestHash").s());
            throw canceled("TransactionConflict");
        }).thenThrow(canceled("ConditionalCheckFailed"));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            Map<String, AttributeValue> item = request.key().get("pk").s().startsWith("QUOTE#")
                    ? Map.of("phase", AttributeValue.fromS("VALIDATING"))
                    : Map.of("requestHash", AttributeValue.fromS(requestHash.get()),
                            "quoteId", AttributeValue.fromS("q-existing"), "version", AttributeValue.fromN("1"));
            return GetItemResponse.builder().item(item).build();
        });

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-10")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(202)
                .body("quoteId", equalTo("q-existing"))
                .body("phase", equalTo("VALIDATING"));
    }

    @Test
    void sdkFailureBeforeDurableAcceptanceIsUnavailable() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(SdkClientException.create("read timed out"));

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-11")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(503)
                .body("code", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    void acceptanceIsLoggedWithTheCorrelationId() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(QuoteResource.class.getName());
        logger.addHandler(capture);
        try {
            given().header("Authorization", writer).header("Idempotency-Key", "contract-12")
                    .header(RequestCorrelation.HEADER, "corr-accepted-1")
                    .contentType(ContentType.JSON).body(CREATE_BODY)
                    .when().post("/v1/quotes")
                    .then().statusCode(202);
        } finally {
            logger.removeHandler(capture);
        }

        assertThat(records).extracting(LogRecord::getMessage)
                .anySatisfy(message -> assertThat(message).contains("correlationId=corr-accepted-1"));
    }

    @Test
    void invalidCreationIsDeclaredInTheContract() {
        given().filter(RESPONSES_ONLY).header("Authorization", writer).header("Idempotency-Key", "contract-3")
                .contentType(ContentType.JSON)
                .body("{\"product\":\"LIFE\",\"risk\":{\"usage\":\"BOAT\"},\"extra\":1}")
                .when().post("/v1/quotes")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.path", hasItem("risk.usage"))
                .body("fieldErrors.path", hasItem("extra"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void unknownReferenceIsAFieldError() {
        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "contract-4")
                .contentType(ContentType.JSON)
                .body(CREATE_BODY.replace("vehicle-hatch-2021", "vehicle-globex-suv-2022"))
                .when().post("/v1/quotes")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("risk.vehicleRef"));
    }

    @Test
    void missingIdempotencyKeyIsAValidationError() {
        given().filter(RESPONSES_ONLY).header("Authorization", writer)
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("Idempotency-Key"));
    }

    @Test
    void unauthenticatedRequestIsDeclaredInTheContract() {
        given().filter(RESPONSES_ONLY).header("Idempotency-Key", "contract-5")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(401)
                .body("code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void idTokenAndForeignClientTokenAreRejected() {
        String idToken = TestTokens.token("ana", "id", TestTokens.CLIENT_ID, "org_org-acme", "perm_quotes_read");
        String otherClient = TestTokens.token("ana", "access", "other-client", "org_org-acme", "perm_quotes_read");

        given().filter(RESPONSES_ONLY).header("Authorization", TestTokens.bearer(idToken))
                .when().get("/v1/me").then().statusCode(401);
        given().filter(RESPONSES_ONLY).header("Authorization", TestTokens.bearer(otherClient))
                .when().get("/v1/me").then().statusCode(401);
        given().filter(RESPONSES_ONLY).header("Authorization", "Bearer not-a-jwt")
                .when().get("/v1/me").then().statusCode(401);
    }

    @Test
    void creationWithoutWritePermissionIsForbidden() {
        given().filter(CONTRACT).header("Authorization", reader).header("Idempotency-Key", "contract-6")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes")
                .then().statusCode(403)
                .body("code", equalTo("FORBIDDEN"));
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void unsupportedMediaTypeIsDeclaredInTheContract() {
        given().filter(RESPONSES_ONLY).header("Authorization", writer).header("Idempotency-Key", "contract-7")
                .contentType(ContentType.TEXT).body("quote")
                .when().post("/v1/quotes")
                .then().statusCode(415);
    }

    @Test
    void versionFollowsTheContract() {
        stubQuote();

        given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/quotes/q-1/versions/1")
                .then().statusCode(200)
                .body("phase", equalTo("QUOTING"))
                .body("outcome", nullValue())
                .body("retryAfterSeconds", equalTo(3))
                .body("revision", equalTo(9))
                .body("insurerResults.status", contains("QUOTED", "PROCESSING"))
                .body("allowedActions", contains("CREATE_VERSION", "SELECT_OFFER"));
    }

    @Test
    void t08_partialVersionWithATimedOutInsurerFollowsTheContract() {
        stubQuote("TERMINAL", "PARTIAL", "TIMED_OUT", "STEP_TIMEOUT");

        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes/q-1/versions/1")
                .then().statusCode(200)
                .body("outcome", equalTo("PARTIAL"))
                .body("outcomeReason", nullValue())
                .body("retryAfterSeconds", nullValue())
                .body("insurerResults.status", contains("QUOTED", "TIMED_OUT"))
                .body("insurerResults[1].reasonCode", equalTo("STEP_TIMEOUT"));
        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes/q-1/versions/1/offers")
                .then().statusCode(200)
                .body("items.insurerId", contains("alpha"));
    }

    @Test
    void offersFollowTheContract() {
        stubQuote();

        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes/q-1/versions/1/offers")
                .then().statusCode(200)
                .body("revision", equalTo(2))
                .body("items[0].selectable", equalTo(true))
                .body("items[0].unavailabilityReason", nullValue())
                .body("nextCursor", nullValue());
    }

    @Test
    void nonNumericOffersLimitIsAValidationError() {
        stubQuote();

        given().filter(RESPONSES_ONLY).header("Authorization", reader)
                .when().get("/v1/quotes/q-1/versions/1/offers?limit=abc")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.path", contains("limit"));
    }

    @Test
    void quoteOfAnotherOrganizationIsNotFound() {
        stubQuote();
        String globex = TestTokens.bearer(TestTokens.access("carla", "org_org-globex", "perm_quotes_read"));

        given().filter(CONTRACT).header("Authorization", globex)
                .when().get("/v1/quotes/q-1/versions/1")
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void missingVersionIsDeclaredInTheContract() {
        stubQuote();

        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes/q-1/versions/2")
                .then().statusCode(404);
    }

    @Test
    void meFollowsTheContract() {
        given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/me")
                .then().statusCode(200)
                .body("organizationId", equalTo("org-acme"))
                .body("userId", equalTo("ana"))
                .body("permissions", contains("quotes:read", "quotes:write", "offers:select"));
    }

    @Test
    void userWithoutOrganizationIsForbidden() {
        String orphan = TestTokens.bearer(TestTokens.access("sem-org", "perm_quotes_read"));

        given().filter(CONTRACT).header("Authorization", orphan)
                .when().get("/v1/me")
                .then().statusCode(403);
    }

    private static TransactionCanceledException canceled(String reason) {
        return TransactionCanceledException.builder().message("canceled").cancellationReasons(
                CancellationReason.builder().code(reason).build(),
                CancellationReason.builder().code("None").build()).build();
    }

    private void stubQuote() {
        stubQuote("QUOTING", null, "PROCESSING", null);
    }

    /** Versão com a oferta da alpha e o resultado informado da beta; {@code outcome} só na fase terminal. */
    private void stubQuote(String phase, String outcome, String betaStatus, String betaReason) {
        String now = Instant.now().toString();
        String validUntil = Instant.now().plusSeconds(1800).toString();
        Map<String, AttributeValue> meta = Map.of(
                "pk", AttributeValue.fromS("QUOTE#q-1"), "sk", AttributeValue.fromS("META"),
                "organizationId", AttributeValue.fromS("org-acme"), "currentVersion", AttributeValue.fromN("1"));
        Map<String, AttributeValue> version = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUOTE#q-1"), "sk", AttributeValue.fromS("V#0001"),
                "snapshot", AttributeValue.fromS(SNAPSHOT), "phase", AttributeValue.fromS(phase),
                "revision", AttributeValue.fromN("4"), "updatedAt", AttributeValue.fromS(now)));
        if (outcome != null) {
            version.put("outcome", AttributeValue.fromS(outcome));
        }
        Map<String, AttributeValue> alpha = Map.of(
                "sk", AttributeValue.fromS("V#0001#RESULT#alpha"), "insurerId", AttributeValue.fromS("alpha"),
                "insurerName", AttributeValue.fromS("Alpha Seguros"), "status", AttributeValue.fromS("QUOTED"),
                "revision", AttributeValue.fromN("2"), "updatedAt", AttributeValue.fromS(now));
        Map<String, AttributeValue> beta = new HashMap<>(Map.of(
                "sk", AttributeValue.fromS("V#0001#RESULT#beta"), "insurerId", AttributeValue.fromS("beta"),
                "insurerName", AttributeValue.fromS("Beta Seguros"), "status", AttributeValue.fromS(betaStatus),
                "revision", AttributeValue.fromN("2"), "updatedAt", AttributeValue.fromS(now)));
        if (betaReason != null) {
            beta.put("reasonCode", AttributeValue.fromS(betaReason));
        }
        Map<String, AttributeValue> offer = new HashMap<>(Map.of(
                "sk", AttributeValue.fromS("V#0001#OFFER#of_1"), "offerId", AttributeValue.fromS("of_1"),
                "insurerId", AttributeValue.fromS("alpha"), "insurerName", AttributeValue.fromS("Alpha Seguros"),
                "premiumTotal", AttributeValue.fromS("2450.90"), "currency", AttributeValue.fromS("BRL"),
                "installments", AttributeValue.fromS("[{\"count\":1,\"amount\":\"2450.90\"},{\"count\":10,\"amount\":\"245.09\"}]"),
                "coverages", AttributeValue.fromS("[{\"code\":\"COLLISION\",\"label\":\"Colisão\",\"insuredAmount\":\"90000.00\"}]"),
                "deductibles", AttributeValue.fromS("[{\"coverageCode\":\"COLLISION\",\"amount\":\"3500.00\"}]"),
                "validUntil", AttributeValue.fromS(validUntil)));
        offer.put("createdAt", AttributeValue.fromS(now));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            boolean isMeta = "QUOTE#q-1".equals(request.key().get("pk").s()) && "META".equals(request.key().get("sk").s());
            return GetItemResponse.builder().item(isMeta ? meta : null).build();
        });
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(0);
            boolean firstVersion = "V#0001".equals(request.expressionAttributeValues().get(":prefix").s());
            return QueryResponse.builder().items(firstVersion ? List.of(version, alpha, beta, offer) : List.of()).build();
        });
    }
}

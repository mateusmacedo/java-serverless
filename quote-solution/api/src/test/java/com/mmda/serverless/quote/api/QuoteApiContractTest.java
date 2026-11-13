package com.mmda.serverless.quote.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Permission;
import io.smallrye.jwt.build.Jwt;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
 * Confere requisições e respostas contra {@code contracts/openapi.yaml}, a API de produto do contrato
 * compartilhado. O base path é zerado porque o servidor do contrato inclui o prefixo do API Gateway.
 */
@QuarkusTest
@WithTestResource(JwtKeyResource.class)
class QuoteApiContractTest {

    static final String SPECIFICATION =
            Path.of("../../contracts/openapi.yaml").toAbsolutePath().toUri().toString();

    static final OpenApiValidationFilter CONTRACT = new OpenApiValidationFilter(OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION)
            .withBasePathOverride("/")
            .build());

    // WHY: nos casos de erro a requisição é inválida de propósito; só a resposta é conferida
    static final OpenApiValidationFilter RESPONSES_ONLY = new OpenApiValidationFilter(OpenApiInteractionValidator
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

    static final String VERSION_BODY = """
            {"expectedVersion":1,"product":"AUTO","applicantRef":"customer-acme-ana",
             "risk":{"vehicleRef":"vehicle-sedan-2019","usage":"PERSONAL","principalDriverRef":"driver-acme-ana"},
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

    @Test
    void t17_newVersionFollowsTheContractAndSupersedesTheCurrentOneInOneTransaction() {
        stubVersionTarget(Map.of());
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "version-1")
                .contentType(ContentType.JSON).body(VERSION_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(202)
                .header("Location", equalTo("/v1/quotes/q-1/versions/2"))
                .body("quoteId", equalTo("q-1"))
                .body("version", equalTo(2))
                .body("phase", equalTo("RECEIVED"))
                .body("statusUrl", equalTo("/v1/quotes/q-1/versions/2"));

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(captor.capture());
        List<TransactWriteItem> writes = captor.getValue().transactItems();
        assertThat(writes).hasSize(6);
        assertThat(writes.get(0).put().item().get("pk").s())
                .isEqualTo("IDEMPOTENCY#org-acme#CREATE_VERSION#q-1#version-1");
        assertThat(writes.get(1).update().conditionExpression())
                .isEqualTo("currentVersion = :expected AND attribute_not_exists(selectionId) AND organizationId = :org");
        assertThat(writes.get(1).update().expressionAttributeValues().get(":expected").n()).isEqualTo("1");
        assertThat(writes.get(2).update().key().get("sk").s()).isEqualTo("V#0001");
        assertThat(writes.get(2).update().updateExpression()).contains("supersededAt");
        assertThat(writes.get(3).put().item().get("sk").s()).isEqualTo("V#0002");
        assertThat(writes.get(3).put().item().get("gsi2pk").s()).isEqualTo("ACTIVE_VERSION");
        assertThat(writes.subList(4, 6)).extracting(write -> write.put().item().get("detailType").s())
                .containsExactly("StartQuoteRequested", "QuoteAccepted");
        assertThat(writes.subList(4, 6)).allSatisfy(write ->
                assertThat(write.put().item().get("version").n()).isEqualTo("2"));
    }

    @Test
    void t17_staleExpectedVersionIsAVersionConflict() {
        stubVersionTarget(Map.of("currentVersion", AttributeValue.fromN("2")));
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(versionCanceled());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "version-2")
                .contentType(ContentType.JSON).body(VERSION_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(409)
                .body("code", equalTo("VERSION_CONFLICT"))
                .body("correlationId", notNullValue());
    }

    @Test
    void newVersionAfterASelectionIsRejected() {
        stubVersionTarget(Map.of("selectionId", AttributeValue.fromS("sel-1")));
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenThrow(versionCanceled());

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "version-3")
                .contentType(ContentType.JSON).body(VERSION_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(409)
                .body("code", equalTo("QUOTE_ALREADY_SELECTED"));
    }

    @Test
    void newVersionResubmittedWithTheSameKeyAndBodyIsAReplay() {
        AtomicReference<String> requestHash = new AtomicReference<>();
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(invocation -> {
            TransactWriteItemsRequest request = invocation.getArgument(0);
            requestHash.set(request.transactItems().getFirst().put().item().get("requestHash").s());
            throw versionCanceled();
        });
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            String pk = ((GetItemRequest) invocation.getArgument(0)).key().get("pk").s();
            String sk = ((GetItemRequest) invocation.getArgument(0)).key().get("sk").s();
            Map<String, AttributeValue> item = pk.startsWith("IDEMPOTENCY#")
                    ? Map.of("requestHash", AttributeValue.fromS(requestHash.get()),
                            "quoteId", AttributeValue.fromS("q-1"), "version", AttributeValue.fromN("2"))
                    : "META".equals(sk) ? versionTargetMeta(Map.of("currentVersion", AttributeValue.fromN("2")))
                    : Map.of("phase", AttributeValue.fromS("VALIDATING"));
            return GetItemResponse.builder().item(item).build();
        });

        given().filter(CONTRACT).header("Authorization", writer).header("Idempotency-Key", "version-4")
                .contentType(ContentType.JSON).body(VERSION_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(202)
                .body("version", equalTo(2))
                .body("phase", equalTo("VALIDATING"));
    }

    @Test
    void invalidNewVersionIsAValidationError() {
        stubVersionTarget(Map.of());

        given().filter(RESPONSES_ONLY).header("Authorization", writer).header("Idempotency-Key", "version-5")
                .contentType(ContentType.JSON).body(VERSION_BODY.replace("\"expectedVersion\":1,", "\"expectedVersion\":0,"))
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(400)
                .body("code", equalTo("VALIDATION_ERROR"))
                .body("fieldErrors.path", contains("expectedVersion"));
        given().filter(RESPONSES_ONLY).header("Authorization", writer).header("Idempotency-Key", "version-6")
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("expectedVersion"));
    }

    @Test
    void newVersionNeedsWritePermissionAndTheOwnerOrganization() {
        stubVersionTarget(Map.of());
        String globex = TestTokens.bearer(TestTokens.access("carla", "org_org-globex", "perm_quotes_read",
                "perm_quotes_write"));

        given().filter(CONTRACT).header("Authorization", reader).header("Idempotency-Key", "version-7")
                .contentType(ContentType.JSON).body(VERSION_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(403);
        given().filter(CONTRACT).header("Authorization", globex).header("Idempotency-Key", "version-8")
                .contentType(ContentType.JSON).body(VERSION_BODY)
                .when().post("/v1/quotes/q-1/versions")
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"));
    }

    @Test
    void t22_everyImplementedRouteIsInTheContractAndEveryContractRouteIsImplemented() throws Exception {
        Set<String> implemented = new TreeSet<>();
        for (Class<?> resource : resourceClasses()) {
            String base = resource.getAnnotation(jakarta.ws.rs.Path.class).value();
            for (Method method : resource.getDeclaredMethods()) {
                String http = method.isAnnotationPresent(GET.class) ? "GET"
                        : method.isAnnotationPresent(POST.class) ? "POST"
                        : method.isAnnotationPresent(PUT.class) ? "PUT"
                        : method.isAnnotationPresent(DELETE.class) ? "DELETE"
                        : method.isAnnotationPresent(PATCH.class) ? "PATCH" : null;
                if (http != null) {
                    jakarta.ws.rs.Path sub = method.getAnnotation(jakarta.ws.rs.Path.class);
                    implemented.add(http + " " + template(base + (sub == null ? "" : sub.value())));
                }
            }
        }
        Set<String> contract = new TreeSet<>();
        contractOperations().forEach((route, operation) -> contract.add(route));

        assertThat(resourceClasses()).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("MeResource", "QuoteResource", "CatalogResource", "OperationsResource");
        assertThat(implemented).containsExactlyInAnyOrderElementsOf(contract);
    }

    @Test
    void t30_everyRouteAnswers403WithoutThePermissionTheContractDeclares() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of()).build());
        int checked = 0;
        for (Map.Entry<String, Operation> entry : contractOperations().entrySet()) {
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) entry.getValue().getExtensions().get("x-permissions");
            assertThat(required).as("x-permissions of %s", entry.getKey()).isNotNull();
            if (required.isEmpty()) {
                continue;
            }
            Permission permission = Arrays.stream(Permission.values())
                    .filter(candidate -> candidate.code().equals(required.getFirst())).findFirst().orElseThrow();
            String[] route = entry.getKey().split(" ", 2);
            String path = route[1].replace("{}", "q_1").replace("/versions/q_1", "/versions/1");
            List<String> others = new ArrayList<>(List.of("org_org-acme"));
            Arrays.stream(Permission.values()).filter(other -> other != permission).forEach(other -> others.add(other.group()));

            request(TestTokens.access("perm-check", others.toArray(String[]::new)), route[0], path)
                    .then().statusCode(403).body("code", equalTo("FORBIDDEN"));
            int allowed = request(TestTokens.access("perm-check", "org_org-acme", permission.group()), route[0], path)
                    .then().extract().statusCode();
            assertThat(allowed).as("%s with %s", entry.getKey(), permission.code()).isNotEqualTo(403);
            checked++;
        }
        assertThat(checked).isEqualTo(13);
        verify(dynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void invalidSignatureIsUnauthenticated() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey otherKey = generator.generateKeyPair().getPrivate();
        String forged = Jwt.issuer(TestTokens.ISSUER).subject("ana").claim("username", "ana")
                .claim("token_use", "access").claim("client_id", TestTokens.CLIENT_ID)
                .claim("cognito:groups", List.of("org_org-acme", "perm_quotes_read"))
                .jws().keyId("test").sign(otherKey);
        String valid = TestTokens.access("ana", "org_org-acme", "perm_quotes_read");
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        given().filter(RESPONSES_ONLY).header("Authorization", TestTokens.bearer(forged))
                .when().get("/v1/me").then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));
        given().filter(RESPONSES_ONLY).header("Authorization", TestTokens.bearer(tampered))
                .when().get("/v1/me").then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));
        given().filter(CONTRACT).header("Authorization", TestTokens.bearer(valid))
                .when().get("/v1/me").then().statusCode(200);
    }

    @Test
    void t22_catalogAnswersExactlyTheSharedFixtures() throws Exception {
        JsonNode pages = new ObjectMapper().readTree(Path.of("../../contracts/fixtures/catalog-pages.json").toFile());
        for (JsonNode exchange : pages.get("exchanges")) {
            String body = given().filter(CONTRACT).header("Authorization", writer)
                    .when().get(exchange.at("/request/path").asText())
                    .then().statusCode(200).extract().asString();

            assertThat(new ObjectMapper().readTree(body)).as(exchange.get("name").asText())
                    .isEqualTo(exchange.at("/response/body"));
        }
    }

    @Test
    void catalogIsScopedToTheOrganizationSearchedAndPaged() {
        String globex = TestTokens.bearer(TestTokens.access("carla", "org_org-globex", "perm_quotes_read",
                "perm_quotes_write"));

        given().filter(CONTRACT).header("Authorization", globex)
                .when().get("/v1/catalog/vehicles")
                .then().statusCode(200)
                .body("items.id", contains("vehicle-globex-suv-2022"));
        given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/catalog/drivers?search=BRUNO")
                .then().statusCode(200)
                .body("items.id", contains("driver-acme-bruno"));
        given().filter(CONTRACT).header("Authorization", writer).queryParam("search", "SEDA 2.0")
                .when().get("/v1/catalog/vehicles")
                .then().statusCode(200)
                .body("items.id", contains("vehicle-sedan-2019"));
        String next = given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/catalog/customers?limit=2")
                .then().statusCode(200)
                .body("items.id", contains("customer-acme-ana", "customer-acme-bruno"))
                .extract().path("nextCursor");
        given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/catalog/customers?limit=2&cursor=" + next)
                .then().statusCode(200)
                .body("items.id", contains("customer-acme-frota"))
                .body("nextCursor", nullValue());
        given().filter(CONTRACT).header("Authorization", writer)
                .when().get("/v1/catalog/coverage-profiles?search=terceiros&limit=1")
                .then().statusCode(200)
                .body("items.code", contains("THEFT_AND_THIRD_PARTY"))
                .body("nextCursor", notNullValue());
        given().filter(RESPONSES_ONLY).header("Authorization", writer)
                .when().get("/v1/catalog/customers?cursor=" + QuoteViews.encodeCursor("customer-globex-carla"))
                .then().statusCode(400)
                .body("fieldErrors.path", contains("cursor"));
        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/catalog/customers")
                .then().statusCode(403);
    }

    @Test
    void listingReadsTheOrganizationIndexWithFiltersAndACursor() {
        Map<String, AttributeValue> first = summary("q-2", "2026-11-12T10:10:00Z", "QUOTING", null, 1);
        Map<String, AttributeValue> second = summary("q-1", "2026-11-12T10:00:00.123456Z", "TERMINAL", "COMPLETED", 2);
        Map<String, AttributeValue> third = summary("q-0", "2026-11-11T18:00:00Z", "TERMINAL", "COMPLETED", 0);
        List<QueryRequest> requests = new CopyOnWriteArrayList<>();
        // NOTE: o filtro de phase e outcome é do DynamoDB; a simulação devolve o que ele devolveria em cada página
        when(dynamoDb.query(any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(0);
            requests.add(request);
            return QueryResponse.builder()
                    .items(request.hasExclusiveStartKey() ? List.of(second, third) : List.of(first, second, third))
                    .build();
        });

        String cursor = given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes?limit=2")
                .then().statusCode(200)
                .body("items.id", contains("q-2", "q-1"))
                .body("items[0].outcome", nullValue())
                .body("items[1].offerCount", equalTo(2))
                .extract().path("nextCursor");
        assertThat(QuoteViews.decodeCursor(cursor)).isEqualTo("2026-11-12T10:00:00.123456Z#q-1");
        QueryRequest firstPage = requests.getLast();
        assertThat(firstPage.indexName()).isEqualTo("gsi1");
        assertThat(firstPage.scanIndexForward()).isFalse();
        assertThat(firstPage.expressionAttributeValues().get(":gpk").s()).isEqualTo("ORG#org-acme");
        assertThat(firstPage.filterExpression()).isNull();

        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes?limit=2&cursor=" + cursor + "&phase=TERMINAL&outcome=COMPLETED"
                        + "&createdFrom=2026-11-12T10:00:00.123456Z&createdTo=2026-11-12T23:59:59Z")
                .then().statusCode(200)
                .body("items.id", contains("q-1"))
                .body("nextCursor", nullValue());
        QueryRequest filtered = requests.getLast();
        assertThat(filtered.exclusiveStartKey().get("pk").s()).isEqualTo("QUOTE#q-1");
        assertThat(filtered.exclusiveStartKey().get("gsi1pk").s()).isEqualTo("ORG#org-acme");
        assertThat(filtered.filterExpression()).isEqualTo("phase = :phase AND outcome = :outcome");
        assertThat(filtered.keyConditionExpression()).contains("BETWEEN :from AND :to");
        assertThat(filtered.expressionAttributeValues().get(":from").s()).isEqualTo("2026-11-12T10:00:00");
        assertThat(filtered.expressionAttributeValues().get(":to").s()).isEqualTo("2026-11-12T23:59:59~");
    }

    @Test
    void invalidListingFiltersAreValidationErrors() {
        given().filter(RESPONSES_ONLY).header("Authorization", reader)
                .when().get("/v1/quotes?phase=DONE&outcome=WON&createdFrom=ontem")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("phase", "outcome", "createdFrom"));
        given().filter(RESPONSES_ONLY).header("Authorization", reader)
                .when().get("/v1/quotes?createdFrom=2026-11-12T10:00:00Z&createdTo=2026-11-11T10:00:00Z")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("createdTo"));
        given().filter(RESPONSES_ONLY).header("Authorization", reader)
                .when().get("/v1/quotes?cursor=" + QuoteViews.encodeCursor("sem-separador"))
                .then().statusCode(400)
                .body("fieldErrors.path", contains("cursor"));
        given().filter(RESPONSES_ONLY).header("Authorization", reader)
                .when().get("/v1/quotes?limit=0")
                .then().statusCode(400);
        verify(dynamoDb, never()).query(any(QueryRequest.class));
    }

    /** O cursor de outra combinação de filtros ou adulterado responde 400, nunca 500 do {@code ExclusiveStartKey}. */
    @Test
    void cursorOutsideThePeriodOrBeyondTheIndexKeyIsAValidationError() {
        when(dynamoDb.query(any(QueryRequest.class)))
                .thenAnswer(invocation -> DynamoDbLimits.query(invocation.getArgument(0), List.of()));
        String cursor = QuoteViews.encodeCursor("2026-11-12T10:00:00.123Z#q-1");

        for (String period : List.of("createdFrom=2026-11-13T00:00:00Z", "createdTo=2026-11-11T23:59:59Z",
                "createdFrom=2026-11-11T00:00:00Z&createdTo=2026-11-11T23:59:59Z")) {
            given().filter(RESPONSES_ONLY).header("Authorization", reader)
                    .when().get("/v1/quotes?cursor=" + cursor + "&" + period)
                    .then().statusCode(400)
                    .body("fieldErrors.path", contains("cursor"));
        }
        given().filter(RESPONSES_ONLY).header("Authorization", reader)
                .when().get("/v1/quotes?cursor=" + QuoteViews.encodeCursor("2026-11-12T10:00:00Z".repeat(52) + "#q-1"))
                .then().statusCode(400)
                .body("fieldErrors.path", contains("cursor"));
        given().filter(CONTRACT).header("Authorization", reader)
                .when().get("/v1/quotes?cursor=" + cursor + "&createdFrom=2026-11-12T10:00:00Z"
                        + "&createdTo=2026-11-12T10:00:00.123Z")
                .then().statusCode(200);
    }

    @Test
    void quoteIdLongerThanAKeyIsNotFoundWithoutReachingTheTable() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            DynamoDbLimits.checkKey(invocation.<GetItemRequest>getArgument(0).key());
            return GetItemResponse.builder().build();
        });
        String path = "/v1/quotes/" + "9".repeat(3000) + "/versions/1";

        given().filter(RESPONSES_ONLY).header("Authorization", writer)
                .when().get(path)
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"));
        given().filter(RESPONSES_ONLY).header("Authorization", writer).header("Idempotency-Key", "long-id")
                .contentType(ContentType.JSON).body("{\"offerId\":\"of_a\"}")
                .when().post(path + "/selection")
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"));
    }

    /** O {@code maxLength} do contrato conta caracteres (code points), não unidades UTF-16. */
    @Test
    void searchLengthCountsCharactersLikeTheContract() {
        given().filter(RESPONSES_ONLY).header("Authorization", writer).queryParam("search", "🚗".repeat(60))
                .when().get("/v1/catalog/vehicles")
                .then().statusCode(200)
                .body("items", hasSize(0));
        given().filter(RESPONSES_ONLY).header("Authorization", writer).queryParam("search", "🚗".repeat(101))
                .when().get("/v1/catalog/vehicles")
                .then().statusCode(400)
                .body("fieldErrors.path", contains("search"));
    }

    private static Map<String, AttributeValue> summary(String quoteId, String createdAt, String phase, String outcome,
            int offers) {
        Map<String, AttributeValue> meta = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUOTE#" + quoteId), "sk", AttributeValue.fromS("META"),
                "quoteId", AttributeValue.fromS(quoteId), "organizationId", AttributeValue.fromS("org-acme"),
                "currentVersion", AttributeValue.fromN("1"), "createdAt", AttributeValue.fromS(createdAt),
                "phase", AttributeValue.fromS(phase), "offerCount", AttributeValue.fromN(Integer.toString(offers)),
                "gsi1pk", AttributeValue.fromS("ORG#org-acme"),
                "gsi1sk", AttributeValue.fromS(createdAt + "#" + quoteId)));
        if (outcome != null) {
            meta.put("outcome", AttributeValue.fromS(outcome));
        }
        return meta;
    }

    private static io.restassured.response.Response request(String token, String method, String path) {
        var specification = given().filter(RESPONSES_ONLY).header("Authorization", TestTokens.bearer(token));
        if ("POST".equals(method)) {
            specification = specification.header("Idempotency-Key", "perm-check").contentType(ContentType.JSON).body("{}");
        }
        return specification.when().request(method, path);
    }

    /** Operações do contrato por rota, {@code MÉTODO /caminho} com os parâmetros trocados por {@code {}}. */
    static Map<String, Operation> contractOperations() {
        OpenAPI openApi = new OpenAPIV3Parser().read(SPECIFICATION);
        Map<String, Operation> operations = new TreeMap<>();
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                .forEach((method, operation) -> operations.put(method.name() + " " + template(path), operation)));
        return operations;
    }

    private static String template(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    /** Toda classe do pacote da API anotada com {@code @Path}, lida do diretório de classes compiladas. */
    private static List<Class<?>> resourceClasses() throws Exception {
        List<Class<?>> resources = new ArrayList<>();
        Path classes = Path.of("target/classes/com/mmda/serverless/quote/api");
        try (var files = java.nio.file.Files.list(classes)) {
            for (Path file : files.filter(candidate -> candidate.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString().replace(".class", "");
                Class<?> type = Class.forName("com.mmda.serverless.quote.api." + name);
                if (type.isAnnotationPresent(jakarta.ws.rs.Path.class)) {
                    resources.add(type);
                }
            }
        }
        return resources;
    }

    /** Cotação {@code q-1} da org-acme na versão 1, sem registro de idempotência para a chave. */
    private void stubVersionTarget(Map<String, AttributeValue> metaChanges) {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(invocation -> {
            GetItemRequest request = invocation.getArgument(0);
            String pk = request.key().get("pk").s();
            Map<String, AttributeValue> item = "QUOTE#q-1".equals(pk) && "META".equals(request.key().get("sk").s())
                    ? versionTargetMeta(metaChanges) : null;
            return GetItemResponse.builder().item(item).build();
        });
    }

    private static Map<String, AttributeValue> versionTargetMeta(Map<String, AttributeValue> changes) {
        Map<String, AttributeValue> meta = new HashMap<>(Map.of(
                "pk", AttributeValue.fromS("QUOTE#q-1"), "sk", AttributeValue.fromS("META"),
                "organizationId", AttributeValue.fromS("org-acme"), "currentVersion", AttributeValue.fromN("1")));
        meta.putAll(changes);
        return meta;
    }

    /** Transação da nova versão cancelada pela condição do {@code META}, a segunda das seis ações. */
    private static TransactionCanceledException versionCanceled() {
        return TransactionCanceledException.builder().message("canceled").cancellationReasons(
                CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("ConditionalCheckFailed").build(),
                CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("None").build(),
                CancellationReason.builder().code("None").build()).build();
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

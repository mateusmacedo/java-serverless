package com.mmda.serverless.provider.api;

import static com.mmda.serverless.provider.api.CallbackFixtures.DECLINED;
import static com.mmda.serverless.provider.api.CallbackFixtures.OFFER;
import static com.mmda.serverless.provider.api.CallbackFixtures.callback;
import static com.mmda.serverless.provider.api.CallbackFixtures.givenInFlightQuery;
import static com.mmda.serverless.provider.api.CallbackFixtures.givenQuery;
import static com.mmda.serverless.provider.api.CallbackFixtures.givenWorkerHalf;
import static com.mmda.serverless.provider.api.CallbackFixtures.newEventId;
import static com.mmda.serverless.provider.api.CallbackFixtures.newProviderRequestId;
import static com.mmda.serverless.provider.api.CallbackFixtures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A API de callbacks com os clients reais da autoconfiguração, contra o Floci
 * (desenho, §8, "Callbacks"): a
 * assinatura, a inbox, o encontro no {@code CORR#} nas duas ordens e o
 * processamento idempotente.
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class CallbackApiFlociIT extends FlociIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private S3Client s3;

        @Test
        void signedCallbackWithTheClientReferenceRecordsTheResultWithTheRequestConsumed() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(providerRequestId);
                String externalEventId = newEventId();

                send(callback(externalEventId, providerRequestId, query.queryId(), OFFER))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.externalEventId").value(externalEventId))
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"))
                                .andExpect(jsonPath("$.duplicate").value(false));

                Map<String, AttributeValue> meta = item("QUERY#" + query.queryId(), "META");
                assertThat(attribute(meta, "state")).contains("COMPLETED");
                assertThat(meta).doesNotContainKeys("gsi2pk", "gsi2sk");
                assertThat(queryItems(query.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
                        assertThat(attribute(attempt, "finishedAt")).isPresent();
                        assertThat(attribute(attempt, "outcome")).contains("QUOTED");
                });
                JsonNode detail = outboxDetail(query.queryId());
                assertThat(detail.path("status").asString()).isEqualTo("QUOTED");
                assertThat(detail.path("workRef").asString()).isEqualTo(query.workRef());
                assertThat(detail.path("insurerId").asString()).isEqualTo("beta");
                assertThat(detail.path("attemptRef").asString()).isEqualTo(Identities.attemptRef(query.queryId(), 1));
                assertThat(detail.path("offer").path("insurerName").asString()).isEqualTo("Beta Seguros");
                assertThat(detail.path("offer").path("coverages").get(0).path("label").asString())
                                .isEqualTo("Colisão, incêndio e roubo");
                assertThat(detail.has("reasonCode")).isFalse();
                String rawResponseRef = detail.path("offer").path("rawResponseRef").asString();
                assertThat(s3.getObjectAsBytes(builder -> builder.bucket(BUCKET).key(rawResponseRef)).asUtf8String())
                                .contains(externalEventId);
                assertThat(queryItems(query.queryId(), "EVIDENCE#")).singleElement()
                                .satisfies(evidence -> assertThat(attribute(evidence, "evidenceId"))
                                                .contains(Identities.evidenceId(query.queryId(), 1)));
                assertThat(item("CONSUMED#" + query.requestEventId(), "META")).isNotEmpty();
                Map<String, AttributeValue> inbox = item("INBOX#beta#" + externalEventId, "META");
                assertThat(attribute(inbox, "processingStatus")).contains("PROCESSED");
                assertThat(attribute(inbox, "queryId")).contains(query.queryId());
                assertThat(inbox).doesNotContainKeys("gsi2pk", "gsi2sk");
        }

        @Test
        void wrongOrMissingSignatureIsRejectedWithoutWritingAnything() throws Exception {
                String externalEventId = newEventId();
                String body = callback(externalEventId, newProviderRequestId(), null, DECLINED);

                mockMvc.perform(post("/v1/providers/beta/callbacks").contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header("X-Signature", sign(body.replace("DECLINED", "OFFER"))))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
                mockMvc.perform(post("/v1/providers/beta/callbacks").contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isUnauthorized());

                assertThat(item("INBOX#beta#" + externalEventId, "META")).isEmpty();
        }

        @Test
        void t11_duplicateCallbackIsRecognizedByTheInboxAndRecordsOneResult() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(providerRequestId);
                String body = callback(newEventId(), providerRequestId, query.queryId(), OFFER);

                send(body).andExpect(status().isOk());
                String firstOutbox = attribute(
                                item("OUTBOX#" + EventIds.insurerResultRecorded(query.queryId()), "META"),
                                "createdAt").orElseThrow();
                send(body).andExpect(status().isOk())
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"))
                                .andExpect(jsonPath("$.duplicate").value(true));

                assertThat(attribute(item("OUTBOX#" + EventIds.insurerResultRecorded(query.queryId()), "META"),
                                "createdAt"))
                                .contains(firstOutbox);
                assertThat(queryItems(query.queryId(), "EVIDENCE#")).hasSize(1);
        }

        @Test
        void t12_callbackAfterTheWorkerHalfIsMatchedThroughTheCorrelation() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(providerRequestId);
                givenWorkerHalf(providerRequestId, query.queryId());
                String externalEventId = newEventId();

                send(callback(externalEventId, providerRequestId, null, DECLINED))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"));

                assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("COMPLETED");
                JsonNode detail = outboxDetail(query.queryId());
                assertThat(detail.path("status").asString()).isEqualTo("DECLINED");
                assertThat(detail.path("reasonCode").asString()).isEqualTo("RISK_NOT_ACCEPTED");
                assertThat(item("CORR#beta#" + providerRequestId, "META").get("pendingEvents").ss())
                                .containsExactly(externalEventId);
        }

        /**
         * O callback só com o {@code providerRequestId} fica na inbox até a metade do
         * worker; a repetição dele, ainda
         * {@code RECEIVED}, refaz a associação. A conclusão pela metade do worker, sem
         * repetição, está no
         * {@code InsurerCallbackIT} da {@code provider-functions}, com o código desta
         * API.
         */
        @Test
        void t12_callbackHeldBeforeTheWorkerHalfIsAssociatedAgainWhenRepeated() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(null);
                String externalEventId = newEventId();
                String body = callback(externalEventId, providerRequestId, null, OFFER);

                send(body).andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"));

                Map<String, AttributeValue> inbox = item("INBOX#beta#" + externalEventId, "META");
                assertThat(attribute(inbox, "processingStatus")).contains("RECEIVED");
                assertThat(attribute(inbox, "gsi2pk")).contains("INBOX_RECEIVED");
                assertThat(attribute(inbox, "payload")).contains(body);
                assertThat(item("CORR#beta#" + providerRequestId, "META").get("pendingEvents").ss())
                                .containsExactly(externalEventId);
                assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("IN_FLIGHT");
                assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(query.queryId()), "META")).isEmpty();

                givenWorkerHalf(providerRequestId, query.queryId());
                send(body).andExpect(status().isOk())
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"))
                                .andExpect(jsonPath("$.duplicate").value(true));

                assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("COMPLETED");
                assertThat(outboxDetail(query.queryId()).path("status").asString()).isEqualTo("QUOTED");
                assertThat(attribute(item("INBOX#beta#" + externalEventId, "META"), "processingStatus"))
                                .contains("PROCESSED");
        }

        /**
         * M4: o reconciler marca {@code UNMATCHED} o callback sem correlação, e a
         * repetição dele ainda o processa.
         */
        @Test
        void repeatedUnmatchedCallbackIsAssociatedAgainInsteadOfBeingTakenAsProcessed() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(null);
                String externalEventId = newEventId();
                String body = callback(externalEventId, providerRequestId, null, OFFER);
                send(body).andExpect(status().isAccepted());
                givenUnmatched(externalEventId);

                send(body).andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.processingStatus").value("RECEIVED"))
                                .andExpect(jsonPath("$.duplicate").value(true));
                givenWorkerHalf(providerRequestId, query.queryId());
                send(body).andExpect(status().isOk())
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"));

                Map<String, AttributeValue> inbox = item("INBOX#beta#" + externalEventId, "META");
                assertThat(attribute(inbox, "processingStatus")).contains("PROCESSED");
                assertThat(inbox).doesNotContainKeys("gsi2pk", "gsi2sk");
                assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("COMPLETED");
                assertThat(outboxDetail(query.queryId()).path("status").asString()).isEqualTo("QUOTED");
        }

        @Test
        void callbackFasterThanThe202FindsTheQueryByTheClientReference() throws Exception {
                CallbackFixtures.Query query = givenInFlightQuery(null);
                String providerRequestId = newProviderRequestId();

                send(callback(newEventId(), providerRequestId, query.queryId(), DECLINED)).andExpect(status().isOk());

                assertThat(queryItems(query.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
                        assertThat(attribute(attempt, "outcome")).contains("DECLINED");
                        assertThat(attribute(attempt, "providerRequestId")).contains(providerRequestId);
                });
                assertThat(outboxDetail(query.queryId()).path("status").asString()).isEqualTo("DECLINED");
        }

        @Test
        void callbackForAQueryAlreadyCompletedOnlyMarksTheInboxProcessed() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenQuery("COMPLETED", providerRequestId);
                String externalEventId = newEventId();

                send(callback(externalEventId, providerRequestId, query.queryId(), OFFER))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"));

                Map<String, AttributeValue> inbox = item("INBOX#beta#" + externalEventId, "META");
                assertThat(attribute(inbox, "processingStatus")).contains("PROCESSED");
                assertThat(inbox).doesNotContainKey("gsi2pk");
                assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(query.queryId()), "META")).isEmpty();
                assertThat(queryItems(query.queryId(), "EVIDENCE#")).isEmpty();
                assertThat(item("CONSUMED#" + query.requestEventId(), "META")).isEmpty();
        }

        @Test
        void unreadableResultIsUnavailableAndFailsTheQuery() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(providerRequestId);

                send(callback(newEventId(), providerRequestId, query.queryId(),
                                "{\"outcome\":\"OFFER\",\"insurerName\":\"Beta Seguros\"}")).andExpect(status().isOk());

                assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("FAILED");
                JsonNode detail = outboxDetail(query.queryId());
                assertThat(detail.path("status").asString()).isEqualTo("UNAVAILABLE");
                assertThat(detail.path("reasonCode").asString()).isEqualTo("INVALID_PROVIDER_RESPONSE");
                assertThat(queryItems(query.queryId(), "ATTEMPT#")).singleElement().satisfies(attempt -> {
                        assertThat(attribute(attempt, "outcome")).contains("UNAVAILABLE");
                        assertThat(attribute(attempt, "errorClass"))
                                        .as("the attempt carries the error class, as when the worker settles the callback")
                                        .contains("INVALID_PROVIDER_RESPONSE");
                });
        }

        /**
         * O tipo JSON errado dentro do {@code result} é resultado ilegível, não
         * envelope inválido (contrato).
         */
        @Test
        void resultWithAFieldOfTheWrongJsonTypeIsStoredAndBecomesUnavailable() throws Exception {
                String providerRequestId = newProviderRequestId();
                CallbackFixtures.Query query = givenInFlightQuery(providerRequestId);
                String externalEventId = newEventId();

                send(callback(externalEventId, providerRequestId, query.queryId(),
                                "{\"outcome\":\"OFFER\",\"insurerName\":\"Beta Seguros\",\"premium\":\"2481.46\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.processingStatus").value("PROCESSED"));

                assertThat(attribute(item("INBOX#beta#" + externalEventId, "META"), "processingStatus"))
                                .contains("PROCESSED");
                assertThat(attribute(item("QUERY#" + query.queryId(), "META"), "state")).contains("FAILED");
                JsonNode detail = outboxDetail(query.queryId());
                assertThat(detail.path("status").asString()).isEqualTo("UNAVAILABLE");
                assertThat(detail.path("reasonCode").asString()).isEqualTo("INVALID_PROVIDER_RESPONSE");
                assertThat(item("CONSUMED#" + query.requestEventId(), "META")).isNotEmpty();
        }

        @Test
        void signedCallbackOutsideTheContractOrFromAnotherProviderIsRefused() throws Exception {
                String missingResult = "{\"externalEventId\":\"bev-1\",\"providerRequestId\":\"beta-1\"}";
                String garbage = "not json";

                send(missingResult).andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));
                send(garbage).andExpect(status().isBadRequest());
                mockMvc.perform(post("/v1/providers/gamma/callbacks").contentType(MediaType.APPLICATION_JSON)
                                .content(DECLINED).header("X-Signature", sign(DECLINED)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("UNKNOWN_PROVIDER"));
                assertThat(item("INBOX#beta#bev-1", "META")).isEmpty();
        }

        /**
         * O que o reconciler grava no callback sem correlação depois de
         * {@code unmatchedCallbackAfterSeconds}.
         */
        private static void givenUnmatched(String externalEventId) {
                DYNAMO_DB.updateItem(builder -> builder.tableName(TABLE)
                                .key(Map.of("pk", AttributeValue.fromS("INBOX#beta#" + externalEventId),
                                                "sk", AttributeValue.fromS("META")))
                                .updateExpression("SET processingStatus = :unmatched, gsi2pk = :index")
                                .expressionAttributeValues(Map.of(
                                                ":unmatched", AttributeValue.fromS("UNMATCHED"),
                                                ":index", AttributeValue.fromS("INBOX_UNMATCHED"))));
        }

        private ResultActions send(String body) throws Exception {
                return mockMvc.perform(post("/v1/providers/beta/callbacks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header("X-Signature", sign(body)));
        }

        private JsonNode outboxDetail(String queryId) {
                Map<String, AttributeValue> outbox = item("OUTBOX#" + EventIds.insurerResultRecorded(queryId), "META");
                assertThat(attribute(outbox, "detailType")).contains("InsurerResultRecorded");
                assertThat(attribute(outbox, "gsi2pk")).contains("OUTBOX_PENDING");
                assertThat(attribute(outbox, "type")).contains("OUTBOX");
                return objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
        }
}

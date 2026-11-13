package com.mmda.serverless.provider.functions;

import static com.mmda.serverless.provider.functions.EnrichmentWorkerIT.sqsEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.mmda.serverless.provider.domain.EventIds;
import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.operations.IssueType;
import com.mmda.serverless.provider.domain.operations.QueryReconcileRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DLQs e reprocessamento no padrão do ADR 0007, no Floci (desenho, §9; PRD,
 * T31). As filas deste teste têm
 * visibilidade de 1 s e {@code maxReceiveCount} 2, para o {@code RedrivePolicy}
 * real levar a mensagem à DLQ sem a
 * espera de 150 s das filas implantadas; o resto é o código do worker e do
 * reconciler.
 */
class DeadLetterReprocessingIT extends FlociIntegrationTest {

        private static final int MAX_RECEIVE_COUNT = 2;
        private static final String INSURER_SOURCE_URL;
        private static final String INSURER_DLQ_URL;
        private static final String ENRICHMENT_SOURCE_URL;
        private static final String ENRICHMENT_DLQ_URL;
        private static final String RECONCILE_SOURCE_URL;
        private static final String RECONCILE_DLQ_URL;

        static {
                INSURER_DLQ_URL = SQS.createQueue(builder -> builder.queueName("it-t31-insurer-requests-dlq"))
                                .queueUrl();
                INSURER_SOURCE_URL = sourceQueue("it-t31-insurer-requests", INSURER_DLQ_URL);
                ENRICHMENT_DLQ_URL = SQS.createQueue(builder -> builder.queueName("it-t31-enrichment-requests-dlq"))
                                .queueUrl();
                ENRICHMENT_SOURCE_URL = sourceQueue("it-t31-enrichment-requests", ENRICHMENT_DLQ_URL);
                RECONCILE_DLQ_URL = SQS.createQueue(builder -> builder.queueName("it-t31-reconcile-requests-dlq"))
                                .queueUrl();
                RECONCILE_SOURCE_URL = sourceQueue("it-t31-reconcile-requests", RECONCILE_DLQ_URL);
        }

        @Autowired
        private InsurerWorkerFunction insurerWorker;

        @Autowired
        private EnrichmentWorkerFunction enrichmentWorker;

        @Autowired
        private DeadLetterReprocessor deadLetters;

        @Autowired
        private OperationsProperties operations;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private OperationLedger operationLedger;

        @DynamicPropertySource
        static void deadLetterRoutes(DynamicPropertyRegistry registry) {
                registry.add("provider.operations.dead-letter-queues[0].name", () -> "insurer-requests");
                registry.add("provider.operations.dead-letter-queues[0].queue-url", () -> INSURER_SOURCE_URL);
                registry.add("provider.operations.dead-letter-queues[0].dead-letter-queue-url", () -> INSURER_DLQ_URL);
                registry.add("provider.operations.dead-letter-queues[1].name", () -> "enrichment-requests");
                registry.add("provider.operations.dead-letter-queues[1].queue-url", () -> ENRICHMENT_SOURCE_URL);
                registry.add("provider.operations.dead-letter-queues[1].dead-letter-queue-url",
                                () -> ENRICHMENT_DLQ_URL);
                registry.add("provider.operations.dead-letter-queues[2].name", () -> "reconcile-requests");
                registry.add("provider.operations.dead-letter-queues[2].queue-url", () -> RECONCILE_SOURCE_URL);
                registry.add("provider.operations.dead-letter-queues[2].dead-letter-queue-url",
                                () -> RECONCILE_DLQ_URL);
        }

        @Test
        void t31_messageThatAlwaysFailsGoesToTheDeadLetterQueueAndIsDiscardedAfterDlqMaxAttempts() {
                TestRequests.Request request = TestRequests.insurer("vehicle-poison", Instant.now().plusSeconds(600));
                String poison = request.body().replace("\"version\":1", "\"version\":\"not-a-number\"");
                assertThat(poison).isNotEqualTo(request.body());
                SQS.sendMessage(builder -> builder.queueUrl(INSURER_SOURCE_URL).messageBody(poison));

                for (int attempt = 1; attempt <= operations.dlqMaxAttempts(); attempt++) {
                        failUntilDeadLettered(INSURER_SOURCE_URL, INSURER_DLQ_URL, insurerWorker::apply);
                        DeadLetterReprocessor.Report report = deadLetters.run(Instant.now());
                        if (attempt < operations.dlqMaxAttempts()) {
                                assertThat(report.redriven()).as("attempt %d is redriven", attempt).isEqualTo(1);
                                Message redriven = receiveOne(INSURER_SOURCE_URL);
                                assertThat(redriven.messageAttributes().get(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE)
                                                .stringValue())
                                                .isEqualTo(Integer.toString(attempt + 1));
                                SQS.changeMessageVisibility(builder -> builder.queueUrl(INSURER_SOURCE_URL)
                                                .receiptHandle(redriven.receiptHandle()).visibilityTimeout(0));
                        } else {
                                assertThat(report.discarded()).as("the last attempt is discarded").isEqualTo(1);
                        }
                }

                assertThat(visible(INSURER_SOURCE_URL)).isZero();
                assertThat(visible(INSURER_DLQ_URL)).isZero();
                String issueId = Identities.issueId(IssueType.DEAD_LETTER, "insurer-requests|" + request.queryId());
                Map<String, AttributeValue> issue = item("ISSUE#" + issueId, "META");
                assertThat(attribute(issue, "status")).contains("OPEN");
                assertThat(attribute(issue, "queryId")).contains(request.queryId());
                assertThat(attribute(issue, "organizationId")).contains("org-acme");
                assertThat(issue).as("a discarded message has nothing left to observe").doesNotContainKey("gsi2pk");
                JsonNode raised = objectMapper.readTree(attribute(item("OUTBOX#" + EventIds.operationIssue(issueId, 1),
                                "META"), "detail").orElseThrow());
                assertThat(raised.path("type").asString()).isEqualTo("DEAD_LETTER");
                assertThat(raised.path("safeSummary").asString()).contains("insurer-requests", "3 tentativas");
                assertThat(PROVIDERS.calls(request.queryId())).isZero();
        }

        @Test
        void t31_redriveOfAnInsurerRequestAlreadyAnsweredRepeatsNoCallAndNoResult() {
                TestRequests.Request request = TestRequests.insurer("vehicle-t31-" + UUID.randomUUID().toString()
                                .substring(0, 8), Instant.now().plusSeconds(600));
                insurerWorker.apply(sqsEvent(request.body()));
                Map<String, AttributeValue> result = item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()),
                                "META");
                assertThat(attribute(result, "detailType")).contains("InsurerResultRecorded");

                redriveFromTheDeadLetterQueue(request.body(), INSURER_SOURCE_URL, INSURER_DLQ_URL,
                                insurerWorker::apply);

                assertThat(PROVIDERS.calls(request.queryId())).isEqualTo(1);
                assertThat(queryItems(request.queryId(), "ATTEMPT#")).hasSize(1);
                assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
                assertThat(item("OUTBOX#" + EventIds.insurerResultRecorded(request.queryId()), "META"))
                                .isEqualTo(result);
        }

        @Test
        void t31_redriveOfAnEnrichmentRequestAlreadyAnsweredRepeatsNoCallAndNoResult() {
                String vehicle = "vehicle-t31-" + UUID.randomUUID().toString().substring(0, 8);
                TestRequests.Request request = TestRequests.enrichment(vehicle, Instant.now().plusSeconds(600));
                enrichmentWorker.apply(sqsEvent(request.body()));
                String eventId = EventIds.enrichmentResolved(request.queryId(), request.workRef());
                Map<String, AttributeValue> result = item("OUTBOX#" + eventId, "META");
                assertThat(attribute(result, "detailType")).contains("EnrichmentResolved");

                redriveFromTheDeadLetterQueue(request.body(), ENRICHMENT_SOURCE_URL, ENRICHMENT_DLQ_URL,
                                enrichmentWorker::apply);

                assertThat(PROVIDERS.enrichmentCalls(vehicle)).isEqualTo(1);
                assertThat(queryItems(request.queryId(), "ATTEMPT#")).hasSize(1);
                assertThat(queryItems(request.queryId(), "EVIDENCE#")).hasSize(1);
                assertThat(item("OUTBOX#" + eventId, "META")).isEqualTo(result);
        }

        @Test
        void deadLetterPastTheDeadlineOfItsRequestIsDiscardedWithAnIssue() {
                TestRequests.Request request = TestRequests.insurer("vehicle-t31-expired",
                                Instant.now().minusSeconds(1));
                SQS.sendMessage(builder -> builder.queueUrl(INSURER_DLQ_URL).messageBody(request.body()));

                DeadLetterReprocessor.Report report = deadLetters.run(Instant.now());

                assertThat(report.discarded()).isEqualTo(1);
                assertThat(visible(INSURER_SOURCE_URL)).isZero();
                String issueId = Identities.issueId(IssueType.DEAD_LETTER, "insurer-requests|" + request.queryId());
                assertThat(attribute(item("ISSUE#" + issueId, "META"), "safeSummary"))
                                .contains("Mensagem de insurer-requests descartada depois do prazo do pedido");
                assertThat(PROVIDERS.calls(request.queryId())).isZero();
        }

        @Test
        void discardedReconciliationFinishesItsRunningOperationAsFailed() {
                String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
                QueryReconcileRequest request = new QueryReconcileRequest(UUID.randomUUID().toString(), operationId,
                                "org-acme", "q_" + UUID.randomUUID().toString().replace("-", ""), "UNKNOWN_QUERY",
                                "operator-ana");
                assertThat(operationLedger.start(request, Instant.now())).isPresent();
                String body = objectMapper.writeValueAsString(Map.of("version", "0", "id", UUID.randomUUID().toString(),
                                "detail-type", "QueryReconcileRequested", "source", "mmda.quote", "detail", request));
                SQS.sendMessage(builder -> builder.queueUrl(RECONCILE_DLQ_URL).messageBody(body)
                                .messageAttributes(Map.of(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE, MessageAttributeValue
                                                .builder()
                                                .dataType("Number")
                                                .stringValue(Integer.toString(operations.dlqMaxAttempts())).build())));

                assertThat(deadLetters.run(Instant.now()).discarded()).isPositive();

                Map<String, AttributeValue> operation = item("OPERATION#" + operationId, "META");
                assertThat(attribute(operation, "status")).as("not RUNNING forever").contains("FAILED");
                assertThat(attribute(operation, "revision")).contains("2");
                JsonNode reconciled = objectMapper
                                .readTree(attribute(item("OUTBOX#" + EventIds.queryReconciled(operationId, 2),
                                                "META"), "detail").orElseThrow());
                assertThat(reconciled.path("status").asString()).isEqualTo("FAILED");
                assertThat(reconciled.path("operationId").asString()).isEqualTo(operationId);
                assertThat(visible(RECONCILE_DLQ_URL)).isZero();
        }

        @Test
        void discardedReconciliationCarriesOnlyAnExistingQueryOfTheOrganizationOfTheRequest() {
                String queryId = "q_" + UUID.randomUUID().toString().replace("-", "");
                DYNAMO_DB.putItem(builder -> builder.tableName(TABLE).item(Map.of(
                                "pk", AttributeValue.fromS("QUERY#" + queryId),
                                "sk", AttributeValue.fromS("META"),
                                "type", AttributeValue.fromS("QUERY"),
                                "organizationId", AttributeValue.fromS("org-acme"),
                                "quoteId", AttributeValue.fromS("quote-dlq-owner"),
                                "state", AttributeValue.fromS("COMPLETED"))));
                Map<String, AttributeValue> query = item("QUERY#" + queryId, "META");
                String own = discardReconciliation("org-acme", queryId);
                String foreign = discardReconciliation("org-globex", queryId);
                String missing = discardReconciliation("org-acme",
                                "q_" + UUID.randomUUID().toString().replace("-", ""));

                await().atMost(Duration.ofSeconds(10)).until(() -> {
                        deadLetters.run(Instant.now());
                        return List.of(own, foreign, missing).stream().allMatch(
                                        operationId -> attribute(item("OPERATION#" + operationId, "META"), "status")
                                                        .orElseThrow().equals("FAILED"));
                });

                JsonNode ownIssue = raisedIssue("reconcile-requests|" + own);
                assertThat(ownIssue.path("organizationId").asString()).isEqualTo("org-acme");
                assertThat(ownIssue.path("queryId").asString()).isEqualTo(queryId);
                assertThat(ownIssue.path("quoteId").asString())
                                .as("the quote of the query, which the request does not carry")
                                .isEqualTo("quote-dlq-owner");
                JsonNode foreignIssue = raisedIssue("reconcile-requests|" + foreign);
                assertThat(foreignIssue.path("organizationId").asString()).isEqualTo("org-globex");
                assertThat(foreignIssue.has("queryId")).as("another organization's query stays out of the issue")
                                .isFalse();
                assertThat(foreignIssue.has("quoteId")).isFalse();
                Map<String, AttributeValue> foreignItem = item("ISSUE#" + Identities.issueId(IssueType.DEAD_LETTER,
                                "reconcile-requests|" + foreign), "META");
                assertThat(foreignItem).doesNotContainKeys("queryId", "quoteId");
                assertThat(raisedIssue("reconcile-requests|" + missing).has("queryId"))
                                .as("a query the provider does not have is treated as nonexistent").isFalse();
                assertThat(item("QUERY#" + queryId, "META")).isEqualTo(query);
        }

        /**
         * Pedido de reconciliação {@code RUNNING} que chega à DLQ no último
         * recebimento; devolve o {@code operationId}.
         */
        private String discardReconciliation(String organizationId, String queryId) {
                String operationId = "op_" + UUID.randomUUID().toString().replace("-", "");
                QueryReconcileRequest request = new QueryReconcileRequest(UUID.randomUUID().toString(), operationId,
                                organizationId, queryId, "UNKNOWN_QUERY", "operator-" + organizationId);
                assertThat(operationLedger.start(request, Instant.now())).isPresent();
                String body = objectMapper.writeValueAsString(Map.of("version", "0", "id", UUID.randomUUID().toString(),
                                "detail-type", "QueryReconcileRequested", "source", "mmda.quote", "detail", request));
                SQS.sendMessage(builder -> builder.queueUrl(RECONCILE_DLQ_URL).messageBody(body)
                                .messageAttributes(Map.of(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE, MessageAttributeValue
                                                .builder()
                                                .dataType("Number")
                                                .stringValue(Integer.toString(operations.dlqMaxAttempts())).build())));
                return operationId;
        }

        /**
         * {@code OperationIssueRaised} da primeira revisão da ocorrência
         * {@code DEAD_LETTER} do alvo, no outbox.
         */
        private JsonNode raisedIssue(String target) {
                String issueId = Identities.issueId(IssueType.DEAD_LETTER, target);
                Map<String, AttributeValue> outbox = item("OUTBOX#" + EventIds.operationIssue(issueId, 1), "META");
                assertThat(attribute(outbox, "detailType")).as("issue of %s", target).contains("OperationIssueRaised");
                return objectMapper.readTree(attribute(outbox, "detail").orElseThrow());
        }

        /**
         * O pedido já atendido chega à DLQ, como depois de uma entrega que caiu entre o
         * efeito e a confirmação.
         */
        private void redriveFromTheDeadLetterQueue(String body, String sourceUrl, String deadLetterUrl,
                        Function<SQSEvent, SQSBatchResponse> worker) {
                SQS.sendMessage(builder -> builder.queueUrl(deadLetterUrl).messageBody(body));
                assertThat(deadLetters.run(Instant.now()).redriven()).isPositive();
                Message redriven = receiveOne(sourceUrl);
                assertThat(redriven.messageAttributes().get(DeadLetterReprocessor.ATTEMPT_ATTRIBUTE).stringValue())
                                .isEqualTo("2");
                assertThat(worker.apply(sqsEvent(redriven.body())).getBatchItemFailures()).isEmpty();
                SQS.deleteMessage(builder -> builder.queueUrl(sourceUrl).receiptHandle(redriven.receiptHandle()));
        }

        /**
         * Recebe e falha a mensagem até o {@code RedrivePolicy} a levar para a DLQ,
         * como o ESM faria.
         */
        private static void failUntilDeadLettered(String sourceUrl, String deadLetterUrl,
                        Function<SQSEvent, SQSBatchResponse> worker) {
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                        for (Message message : SQS.receiveMessage(builder -> builder.queueUrl(sourceUrl)
                                        .maxNumberOfMessages(1).visibilityTimeout(1).waitTimeSeconds(1)).messages()) {
                                assertThat(worker.apply(sqsEvent(message.body())).getBatchItemFailures()).hasSize(1);
                        }
                        return visible(deadLetterUrl) == 1;
                });
        }

        private static Message receiveOne(String queueUrl) {
                List<Message> messages = SQS.receiveMessage(builder -> builder.queueUrl(queueUrl)
                                .maxNumberOfMessages(1).waitTimeSeconds(5).messageAttributeNames("All")).messages();
                assertThat(messages).hasSize(1);
                return messages.getFirst();
        }

        private static int visible(String queueUrl) {
                return Integer.parseInt(SQS.getQueueAttributes(builder -> builder.queueUrl(queueUrl)
                                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                                .attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        }

        private static String sourceQueue(String name, String deadLetterUrl) {
                String redrive = "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"%d\"}"
                                .formatted(queueArn(deadLetterUrl), MAX_RECEIVE_COUNT);
                return SQS.createQueue(builder -> builder.queueName(name).attributes(Map.of(
                                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                                QueueAttributeName.REDRIVE_POLICY, redrive))).queueUrl();
        }
}

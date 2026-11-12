package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.mmda.serverless.quote.domain.Deadlines;
import com.mmda.serverless.quote.domain.Insurers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Teste de configuração (§6, §9, §12 e §13 do desenho) sobre o {@code serverless.yml}, em cada stage: o que o Floci
 * não confere, como a invariante de orçamento que substitui o {@code TimeoutSecondsPath}, a relação visibilidade ×
 * timeout do ESM, os alarmes e o destino deles, os parâmetros do stage {@code dev} e o IAM dos papéis.
 */
class FunctionTimingConfigurationTest {

    private static final Pattern PARAM = Pattern.compile("\\$\\{param:([A-Za-z]+)}");
    /** Referência a parâmetro com ou sem fallback, como {@code ${param:x}} e {@code ${param:x, null}}. */
    private static final Pattern PARAM_REFERENCE = Pattern.compile("\\$\\{param:([A-Za-z]+)(,[^}]*)?}");
    private static final List<String> LAMBDA_INVOCATION_ERRORS = List.of("Lambda.ServiceException",
            "Lambda.AWSLambdaException", "Lambda.SdkClientException", "Lambda.TooManyRequestsException");

    private static String text;
    private static Map<String, Object> serverless;

    @BeforeAll
    static void load() throws IOException {
        text = Files.readString(Path.of("../serverless.yml"));
        serverless = new Yaml(new IgnoringTagsConstructor()).load(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void stepBudgetFitsTheQuoteDeadline(String stage) {
        Map<String, Object> params = params(stage);
        int rounds = (int) Math.ceil((double) param(params, "maxInsurers") / param(params, "mapConcurrency"));
        int budget = param(params, "enrichmentTimeoutSeconds") + rounds * param(params, "insurerTimeoutSeconds")
                + param(params, "stepsMarginSeconds");

        assertThat(budget).isLessThanOrEqualTo(param(params, "quoteDeadlineSeconds"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void workflowTimeoutIsTheDeadlinePlusTheMargin(String stage) {
        Map<String, Object> params = params(stage);

        assertThat(param(params, "workflowTimeoutSeconds"))
                .isEqualTo(param(params, "quoteDeadlineSeconds") + param(params, "workflowMarginSeconds"));
        assertThat(resolve(definition().get("TimeoutSeconds"), params)).isEqualTo(param(params, "workflowTimeoutSeconds"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void mapConcurrencyAndInsurerLimitComeFromTheParameters(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> map = map(states(definition()).get("QuoteInsurers"));

        assertThat(resolve(map.get("MaxConcurrency"), params)).isEqualTo(param(params, "mapConcurrency"));
        assertThat(environment().get("MAX_INSURERS")).isEqualTo("${param:maxInsurers}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void budgetCoversEveryLabInsurerSoNoSelectedInsurerIsCutByTheLimit(String stage) {
        Map<String, Object> params = params(stage);
        int insurers = Insurers.all().size();
        int rounds = (int) Math.ceil((double) insurers / param(params, "mapConcurrency"));

        assertThat(param(params, "maxInsurers")).as("sel-1 selects alpha and beta for personal use (T01)")
                .isGreaterThanOrEqualTo(insurers);
        assertThat(param(params, "enrichmentTimeoutSeconds") + rounds * param(params, "insurerTimeoutSeconds")
                + param(params, "stepsMarginSeconds")).isLessThanOrEqualTo(param(params, "quoteDeadlineSeconds"));
    }

    @Test
    void quoteInsurersMapDispatchesEachSelectedInsurerAndRecordsItsOwnFailure() {
        Map<String, Object> states = states(definition());
        Map<String, Object> map = map(states.get("QuoteInsurers"));

        assertThat(map.get("Type")).isEqualTo("Map");
        assertThat(map.get("ItemsPath")).isEqualTo("$.selection.insurers");
        assertThat(map(map.get("ItemSelector"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "quoteId.$", "$.quoteId", "version.$", "$.version", "insurerId.$", "$$.Map.Item.Value",
                "executionArn.$", "$$.Execution.Id"));
        assertThat(map).as("results stay in DynamoDB (ADR 0014)").containsEntry("ResultPath", null);
        assertThat(map.get("Next")).isEqualTo("ConsolidateQuote");

        Map<String, Object> processor = map(map.get("ItemProcessor"));
        Map<String, Object> request = map(states(processor).get(processor.get("StartAt")));
        assertThat(request.get("Resource")).isEqualTo("arn:aws:states:::lambda:invoke.waitForTaskToken");
        Map<String, Object> payload = map(map(request.get("Parameters")).get("Payload"));
        assertThat(payload).containsEntry("kind", "INSURER").containsEntry("insurerId.$", "$.insurerId");
        List<Object> catches = list(request.get("Catch"));
        assertThat(catches).extracting(handler -> map(handler).get("ErrorEquals"))
                .as("States.ALL comes last").containsExactly(List.of("States.Timeout"), List.of("States.ALL"));
        assertThat(recordedOutcome(processor, catches.get(0))).isEqualTo("TIMED_OUT");
        assertThat(recordedOutcome(processor, catches.get(1))).isEqualTo("UNAVAILABLE");

        Map<String, Object> hasInsurers = map(states.get("HasInsurers"));
        assertThat(map(list(hasInsurers.get("Choices")).getFirst()).get("Next")).isEqualTo("QuoteInsurers");
        assertThat(hasInsurers.get("Default")).as("no applicable insurer (T26)").isEqualTo("ConsolidateQuote");
    }

    @Test
    void timeoutsAreStaticBecauseFlociIgnoresTimeoutSecondsPath() {
        String machine = definition().toString();

        assertThat(machine).doesNotContain("TimeoutSecondsPath", "HeartbeatSecondsPath");
    }

    @Test
    void everyTaskDeclaresTimeoutAndRetriesOnlyLambdaInvocationFailures() {
        List<Map<String, Object>> tasks = tasks(definition());

        assertThat(tasks).hasSize(9);
        for (Map<String, Object> task : tasks) {
            assertThat(task).containsKey("TimeoutSeconds");
            List<Object> retries = list(task.get("Retry"));
            assertThat(retries).hasSize(1);
            assertThat(list(map(retries.getFirst()).get("ErrorEquals"))).containsExactlyElementsOf(LAMBDA_INVOCATION_ERRORS);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void waitsUseTheStepParametersAndPassTheSameTimeoutToTheDispatch(String stage) {
        Map<String, Object> params = params(stage);
        for (Map<String, Object> task : tasks(definition())) {
            String resource = (String) task.get("Resource");
            Map<String, Object> payload = map(map(task.get("Parameters")).get("Payload"));
            if (resource.endsWith("waitForTaskToken")) {
                assertThat(task.get("TimeoutSeconds")).isIn("${param:enrichmentTimeoutSeconds}",
                        "${param:insurerTimeoutSeconds}");
                assertThat(payload.get("timeoutSeconds")).isEqualTo(task.get("TimeoutSeconds"));
            } else {
                assertThat(resolve(task.get("TimeoutSeconds"), params)).isEqualTo(functionTimeout("workflowSteps", params));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void queueVisibilityCoversTheConsumerTimeoutWithRedrive(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> resources = map(map(serverless.get("resources")).get("Resources"));
        int consumers = 0;
        for (Map.Entry<String, Object> function : map(serverless.get("functions")).entrySet()) {
            for (Object event : list(map(function.getValue()).getOrDefault("events", List.of()))) {
                Object sqs = map(event).get("sqs");
                if (sqs == null) {
                    continue;
                }
                consumers++;
                String queue = ((String) map(sqs).get("arn")).replace(".Arn", "");
                Map<String, Object> properties = map(map(resources.get(queue)).get("Properties"));
                int visibility = (Integer) resolve(properties.get("VisibilityTimeout"), params);
                int timeout = functionTimeout(function.getKey(), params);
                assertThat(visibility).as("VisibilityTimeout of %s", queue).isGreaterThanOrEqualTo(timeout);
                assertThat(visibility).as("AWS recommends about 6 times the timeout").isGreaterThanOrEqualTo(6 * timeout);
                assertThat(map(properties.get("RedrivePolicy"))).containsKeys("deadLetterTargetArn", "maxReceiveCount");
                assertThat(map(event).get("sqs")).extracting(value -> map(value).get("batchSize")).isEqualTo(1);
            }
        }
        assertThat(consumers).as("resume (results) and issue-projector (issues, M5)").isEqualTo(2);
    }

    @Test
    void relayStreamKeepsOneRecordFailureFromBlockingTheShard() {
        Map<String, Object> stream = map(map(list(map(map(serverless.get("functions")).get("outboxRelay"))
                .get("events")).getFirst()).get("stream"));

        assertThat(stream.get("batchSize")).isEqualTo(100);
        assertThat(stream.get("maximumRetryAttempts")).isEqualTo(3);
        assertThat(stream.get("bisectBatchOnFunctionError")).isEqualTo(true);
        assertThat(stream.get("functionResponseType")).isEqualTo("ReportBatchItemFailures");
        assertThat(map(stream.get("destinations"))).containsKey("onFailure");
        Map<String, Object> filter = map(list(stream.get("filterPatterns")).getFirst());
        assertThat(filter.get("eventName")).isEqualTo(List.of("INSERT"));
        assertThat(filter.toString()).contains("OUTBOX");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void insurerRequestDeadlineIsCappedByTheRemainingQuoteDeadline(String stage) {
        Map<String, Object> params = params(stage);
        Instant acceptedAt = Instant.parse("2026-11-12T10:00:00Z");
        Instant versionDeadline = Deadlines.versionDeadline(acceptedAt,
                Duration.ofSeconds(param(params, "quoteDeadlineSeconds")));
        Duration insurerWait = Duration.ofSeconds(param(params, "insurerTimeoutSeconds"));
        Instant firstDispatch = acceptedAt.plusSeconds(param(params, "enrichmentTimeoutSeconds"));
        int rounds = (int) Math.ceil((double) param(params, "maxInsurers") / param(params, "mapConcurrency"));
        Instant lastDispatch = firstDispatch.plus(insurerWait.multipliedBy(rounds - 1L));

        assertThat(Deadlines.requestDeadline(firstDispatch, insurerWait, versionDeadline))
                .isEqualTo(firstDispatch.plus(insurerWait));
        assertThat(Deadlines.requestDeadline(lastDispatch, insurerWait, versionDeadline))
                .as("the last Map round still ends before the steps margin")
                .isBeforeOrEqualTo(versionDeadline.minusSeconds(param(params, "stepsMarginSeconds")));
        Instant lateDispatch = versionDeadline.minusSeconds(5);
        assertThat(Deadlines.requestDeadline(lateDispatch, insurerWait, versionDeadline))
                .as("a late dispatch only gets what is left of the quote deadline").isEqualTo(versionDeadline);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void everyDeadLetterQueueAndTheStreamFailuresHaveVolumeAndAgeAlarms(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> resources = resources();
        List<String> monitored = new ArrayList<>();
        for (Object resource : resources.values()) {
            Map<String, Object> properties = map(map(resource).get("Properties"));
            if (properties != null && properties.get("RedrivePolicy") != null) {
                monitored.add(logicalId(map(properties.get("RedrivePolicy")).get("deadLetterTargetArn")));
            }
        }
        monitored.add("StreamFailuresQueue");

        assertThat(monitored).containsExactlyInAnyOrder("ResultsDeadLetterQueue", "IssuesDeadLetterQueue",
                "StreamFailuresQueue");
        for (String queue : monitored) {
            Object queueName = map(map(resources.get(queue)).get("Properties")).get("QueueName");
            Map<Object, Map<String, Object>> alarms = new HashMap<>();
            for (Map<String, Object> alarm : alarms()) {
                if ("AWS/SQS".equals(alarm.get("Namespace"))
                        && queueName.equals(map(list(alarm.get("Dimensions")).getFirst()).get("Value"))) {
                    alarms.put(alarm.get("MetricName"), alarm);
                }
            }
            assertThat(alarms.keySet()).as("alarms on %s", queue)
                    .containsExactlyInAnyOrder("ApproximateNumberOfMessagesVisible", "ApproximateAgeOfOldestMessage");
            assertThat(resolve(alarms.get("ApproximateNumberOfMessagesVisible").get("Threshold"), params))
                    .isEqualTo(param(params, "dlqAlarmMessages"));
            assertThat(resolve(alarms.get("ApproximateAgeOfOldestMessage").get("Threshold"), params))
                    .isEqualTo(param(params, "dlqAlarmAgeSeconds"));
            assertThat(alarms.values()).allSatisfy(alarm -> assertThat(alarm.get("Statistic")).isEqualTo("Maximum"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void outboxAgeAndExpiredTaskAlarmsWatchTheMetricsTheReconcilerEmits(String stage) {
        Map<String, Object> params = params(stage);
        assertThat(environment()).containsEntry("METRICS_NAMESPACE", "${self:custom.metricsNamespace}")
                .containsEntry("STAGE", "${sls:stage}");
        Map<String, Object> outbox = alarm("${self:custom.metricsNamespace}", ReconcilerMetrics.OUTBOX_PENDING_AGE_SECONDS);
        Map<String, Object> expired = alarm("${self:custom.metricsNamespace}", ReconcilerMetrics.EXPIRED_TASKS);

        assertThat(resolve(outbox.get("Threshold"), params)).isEqualTo(param(params, "outboxAlarmAgeSeconds"));
        assertThat(outbox.get("Statistic")).isEqualTo("Maximum");
        assertThat(resolve(expired.get("Threshold"), params)).isEqualTo(param(params, "expiredTasksAlarmThreshold"));
        assertThat(expired.get("Statistic")).isEqualTo("Sum");
        for (Map<String, Object> alarm : List.of(outbox, expired)) {
            assertThat(map(list(alarm.get("Dimensions")).getFirst()))
                    .containsEntry("Name", ReconcilerMetrics.STAGE_DIMENSION).containsEntry("Value", "${sls:stage}");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void alarmThresholdsAndReconcilerParametersFollowTheDesign(String stage) {
        Map<String, Object> params = params(stage);
        assertThat(param(params, "dlqAlarmMessages")).isEqualTo(1);
        assertThat(param(params, "dlqAlarmAgeSeconds")).isEqualTo(300);
        assertThat(param(params, "outboxAlarmAgeSeconds")).isEqualTo(120);
        assertThat(param(params, "expiredTasksAlarmThreshold")).isEqualTo(1);
        assertThat(param(params, "dlqMaxAttempts")).isEqualTo(3);
        assertThat(param(params, "stuckVersionMarginSeconds")).isEqualTo(120);
        assertThat(param(params, "resumingRetryAfterSeconds")).as("the resume had its whole timeout to finish")
                .isGreaterThan(functionTimeout("resume", params));
        assertThat(functionTimeout("reconciler", params)).as("one run fits the 1 minute schedule")
                .isLessThanOrEqualTo(60);
        for (Map<String, Object> alarm : alarms()) {
            assertThat(alarm.get("AlarmName").toString()).startsWith("${self:service}-${sls:stage}-");
            assertThat(alarm).containsEntry("ComparisonOperator", "GreaterThanOrEqualToThreshold")
                    .containsEntry("TreatMissingData", "notBreaching");
        }
    }

    @Test
    void reconcilerRoleCoversTheM4DutiesAndTheApiCanWriteANewVersion() {
        List<Map<String, Object>> statements = statements("ReconcilerRole");

        assertThat(actionsOn(statements, "ResultsDeadLetterQueue.Arn"))
                .as("the DLQ has no event source mapping, so no sqs:GetQueueAttributes")
                .containsExactlyInAnyOrder("sqs:ReceiveMessage", "sqs:DeleteMessage");
        assertThat(actionsOn(statements, "ResultsQueue.Arn")).containsExactly("sqs:SendMessage");
        assertThat(statements.stream().flatMap(statement -> actions(statement).stream()))
                .contains("states:DescribeExecution", "states:GetExecutionHistory", "states:SendTaskSuccess",
                        "secretsmanager:GetSecretValue",
                        "dynamodb:PutItem", "dynamodb:UpdateItem");
        assertThat(statements("ApiRole").stream().flatMap(statement -> actions(statement).stream()))
                .contains("dynamodb:UpdateItem");
        assertThat(environment()).containsKeys("RESULTS_QUEUE_URL", "RESULTS_DLQ_URL", "DLQ_MAX_ATTEMPTS",
                "STUCK_VERSION_MARGIN_SECONDS", "RESUMING_RETRY_AFTER_SECONDS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void issuesQueueReceivesTheProviderOperationalEventsAndTheReconcilerReprocessesItsDlq(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> rule = map(map(resources().get("ProviderIssuesRule")).get("Properties"));
        assertThat(map(rule.get("EventPattern"))).containsEntry("source", List.of("mmda.provider"))
                .containsEntry("detail-type", List.of("QueryReconciled", "OperationIssueRaised", "OperationIssueUpdated"));
        assertThat(logicalId(map(list(rule.get("Targets")).getFirst()).get("Arn"))).isEqualTo("IssuesQueue");
        Map<String, Object> policy = map(list(map(map(map(resources().get("IssuesQueuePolicy")).get("Properties"))
                .get("PolicyDocument")).get("Statement")).getFirst());
        assertThat(map(map(policy.get("Condition")).get("ArnEquals")).get("aws:SourceArn"))
                .isEqualTo("ProviderIssuesRule.Arn");

        Map<String, Object> projector = map(map(serverless.get("functions")).get("issueProjector"));
        assertThat(map(projector.get("environment"))).containsEntry("QUARKUS_LAMBDA_HANDLER", "issue-projector");
        assertThat(projector).doesNotContainKey("memorySize");
        Map<String, Object> sqs = map(map(list(projector.get("events")).getFirst()).get("sqs"));
        assertThat(logicalId(sqs.get("arn"))).isEqualTo("IssuesQueue");
        assertThat(sqs).containsEntry("functionResponseType", "ReportBatchItemFailures");
        assertThat(functionTimeout("issueProjector", params)).isEqualTo(functionTimeout("resume", params));
        assertThat(param(params, "issuesVisibilityTimeoutSeconds")).isEqualTo(90);
        assertThat(param(params, "issuesMaxReceiveCount")).isEqualTo(5);
        assertThat(statements("IssueProjectorRole").stream().flatMap(statement -> actions(statement).stream()))
                .containsExactlyInAnyOrder("dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "sqs:ReceiveMessage",
                        "sqs:DeleteMessage", "sqs:GetQueueAttributes");

        List<Map<String, Object>> reconciler = statements("ReconcilerRole");
        assertThat(actionsOn(reconciler, "IssuesDeadLetterQueue.Arn"))
                .as("the DLQ has no event source mapping, so no sqs:GetQueueAttributes")
                .containsExactlyInAnyOrder("sqs:ReceiveMessage", "sqs:DeleteMessage");
        assertThat(actionsOn(reconciler, "IssuesQueue.Arn")).containsExactly("sqs:SendMessage");
        assertThat(environment()).containsKeys("ISSUES_QUEUE_URL", "ISSUES_DLQ_URL");
        assertThat(statements("ApiRole").stream().flatMap(statement -> actions(statement).stream()))
                .as("the selection checks the offer in its transaction").contains("dynamodb:ConditionCheckItem");
    }

    /** O Serverless falha na resolução de um {@code ${param:x}} sem valor no stage e sem fallback. */
    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void everyReferencedParameterHasAValueInTheStage(String stage) {
        Map<String, Object> params = params(stage);
        Set<String> referenced = new TreeSet<>();
        Matcher matcher = PARAM_REFERENCE.matcher(text);
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                referenced.add(matcher.group(1));
            }
        }

        assertThat(referenced).contains("awsEndpointUrl", "jwtIssuerBase", "alarmEmail", "insurerTimeoutSeconds");
        assertThat(referenced).allSatisfy(name -> assertThat(params.get(name)).as("param %s in %s", name, stage)
                .isNotNull());
    }

    /**
     * WHY: o SDK lê o {@code AWS_ENDPOINT_URL} sozinho e trataria um valor vazio como endpoint, então fora do Floci
     * a condição tira a variável; o issuer é o do Cognito da região, e a espera da seguradora é a da AWS (§12).
     */
    @Test
    void onlyTheFlociDifferencesLiveInTheLocalStage() {
        Map<String, Object> local = map(map(serverless.get("params")).get("local"));
        Map<String, Object> dev = params("dev");

        assertThat(local).containsOnlyKeys("awsEndpointUrl", "jwtIssuerBase", "insurerTimeoutSeconds")
                .containsEntry("awsEndpointUrl", "http://floci:4566")
                .containsEntry("jwtIssuerBase", "http://floci:4566")
                .containsEntry("insurerTimeoutSeconds", 20);
        assertThat(dev).containsEntry("awsEndpointUrl", "")
                .containsEntry("jwtIssuerBase", "https://cognito-idp.${aws:region}.amazonaws.com")
                .containsEntry("insurerTimeoutSeconds", 90);
        assertThat(dev.values()).as("no Floci address outside the local stage")
                .noneMatch(value -> String.valueOf(value).matches(".*(floci|localhost|4566|_user_request_).*"));
        assertThat(environment().get("AWS_ENDPOINT_URL"))
                .isEqualTo(List.of("HasAwsEndpointUrl", "${param:awsEndpointUrl}", "AWS::NoValue"));
        assertThat(conditions().get("HasAwsEndpointUrl")).isEqualTo(List.of(List.of("${param:awsEndpointUrl}", "")));
        assertThat(map(map(map(serverless.get("functions")).get("api")).get("environment")).get("JWT_ISSUER"))
                .isEqualTo(List.of("", List.of("${param:jwtIssuerBase}/", "QuoteUserPool")));
    }

    @Test
    void everyAlarmNotifiesTheAlarmsTopicAndTheEmailSubscriptionIsOptional() {
        Map<String, Object> topic = map(resources().get("AlarmsTopic"));
        Map<String, Object> subscription = map(resources().get("AlarmsEmailSubscription"));

        assertThat(topic.get("Type")).isEqualTo("AWS::SNS::Topic");
        assertThat(map(topic.get("Properties")).get("TopicName")).isEqualTo("${self:service}-${sls:stage}-alarms");
        assertThat(alarms()).as("the 8 alarms of §2").hasSize(8).allSatisfy(alarm -> assertThat(alarm.get("AlarmActions"))
                .as("AlarmActions of %s", alarm.get("AlarmName")).isEqualTo(List.of("AlarmsTopic")));
        assertThat(subscription).containsEntry("Type", "AWS::SNS::Subscription")
                .containsEntry("Condition", "HasAlarmEmail");
        assertThat(map(subscription.get("Properties"))).containsEntry("TopicArn", "AlarmsTopic")
                .containsEntry("Protocol", "email").containsEntry("Endpoint", "${param:alarmEmail}");
        assertThat(conditions().get("HasAlarmEmail")).isEqualTo(List.of(List.of("${param:alarmEmail}", "")));
        assertThat(params("dev")).as("no subscription unless a stage sets the e-mail").containsEntry("alarmEmail", "");
    }

    /**
     * WHY: o papel do {@code reconciler} é também o de execução do agendamento; com papel próprio, o Serverless não
     * acrescenta a confiança no Scheduler nem a invocação, e o Floci não aplica IAM (§13).
     */
    @Test
    void theSchedulerAssumesTheReconcilerRoleOnlyToInvokeTheReconciler() {
        Map<String, Object> function = map(map(serverless.get("functions")).get("reconciler"));
        Map<String, Object> schedule = map(map(list(function.get("events")).getFirst()).get("schedule"));
        assertThat(schedule).containsEntry("method", "scheduler");
        assertThat(function).containsEntry("role", "ReconcilerRole");

        List<Map<String, Object>> trust = trust("ReconcilerRole");
        assertThat(trust).extracting(statement -> map(statement.get("Principal")).get("Service"))
                .containsExactlyInAnyOrder("lambda.amazonaws.com", "scheduler.amazonaws.com");
        Map<String, Object> scheduler = trust.stream()
                .filter(statement -> "scheduler.amazonaws.com".equals(map(statement.get("Principal")).get("Service")))
                .findFirst().orElseThrow();
        assertThat(map(map(scheduler.get("Condition")).get("StringEquals")))
                .as("confused deputy").containsEntry("aws:SourceAccount", "${aws:accountId}");

        String reconciler = "arn:aws:lambda:${aws:region}:${aws:accountId}:function:${self:service}-${sls:stage}-reconciler";
        assertThat(statements("ReconcilerRole").stream().filter(statement -> actions(statement).contains("lambda:InvokeFunction")))
                .singleElement()
                .satisfies(statement -> assertThat(statement.get("Resource")).isEqualTo(List.of(reconciler, reconciler + ":*")));
        for (Object other : map(serverless.get("functions")).values()) {
            String role = (String) map(other).get("role");
            if (!"ReconcilerRole".equals(role)) {
                assertThat(trust(role)).as("trust of %s", role).extracting(statement -> map(statement.get("Principal")).get("Service"))
                        .containsExactly("lambda.amazonaws.com");
                assertThat(statements(role)).as("%s invokes no function", role)
                        .noneMatch(statement -> actions(statement).contains("lambda:InvokeFunction"));
            }
        }
    }

    /** Cada papel escreve só no grupo de log da própria função, criado pelo Serverless, sem logs em {@code *}. */
    @Test
    void everyRoleWritesOnlyToTheLogGroupOfItsOwnFunction() {
        Map<String, Object> functions = map(serverless.get("functions"));
        Set<Object> roles = new TreeSet<>();

        for (Map.Entry<String, Object> function : functions.entrySet()) {
            String role = (String) map(function.getValue()).get("role");
            roles.add(role);
            Map<String, Object> properties = map(map(resources().get(role)).get("Properties"));
            assertThat(properties).as(role).doesNotContainKey("ManagedPolicyArns");
            List<Object> policies = list(properties.get("Policies"));
            assertThat(policies).as("business and logs policies of %s", role).hasSize(2);
            List<Object> logs = list(map(map(policies.get(1)).get("PolicyDocument")).get("Statement"));
            assertThat(logs).as("logs policy of %s", role).singleElement().satisfies(statement -> {
                assertThat(actions(map(statement))).containsExactlyInAnyOrder("logs:CreateLogStream", "logs:PutLogEvents");
                assertThat(map(statement).get("Resource"))
                        .isEqualTo("${self:custom.functionLogGroupArn}-" + function.getKey() + ":*");
            });
            assertThat(statements(role)).as("%s writes logs only through its logs policy", role)
                    .noneMatch(statement -> actions(statement).stream().anyMatch(action -> action.startsWith("logs:")));
        }
        assertThat(roles).as("one role per function").hasSize(functions.size());
        assertThat(map(serverless.get("custom")).get("functionLogGroupArn"))
                .isEqualTo("arn:aws:logs:${aws:region}:${aws:accountId}:log-group:/aws/lambda/${self:service}-${sls:stage}");
    }

    /** Parâmetros como o Serverless os resolve no stage: os do stage sobre os do {@code default}. */
    private static Map<String, Object> params(String stage) {
        Map<String, Object> all = map(serverless.get("params"));
        Map<String, Object> merged = new HashMap<>(map(all.get("default")));
        if (all.containsKey(stage)) {
            merged.putAll(map(all.get(stage)));
        }
        return merged;
    }

    private static Map<String, Object> resources() {
        return map(map(serverless.get("resources")).get("Resources"));
    }

    private static Map<String, Object> conditions() {
        return map(map(serverless.get("resources")).get("Conditions"));
    }

    private static List<Map<String, Object>> alarms() {
        List<Map<String, Object>> alarms = new ArrayList<>();
        for (Object resource : resources().values()) {
            if ("AWS::CloudWatch::Alarm".equals(map(resource).get("Type"))) {
                alarms.add(map(map(resource).get("Properties")));
            }
        }
        return alarms;
    }

    private static Map<String, Object> alarm(String namespace, String metric) {
        return alarms().stream()
                .filter(alarm -> namespace.equals(alarm.get("Namespace")) && metric.equals(alarm.get("MetricName")))
                .findFirst().orElseThrow(() -> new AssertionError("no alarm on " + metric));
    }

    private static List<Map<String, Object>> statements(String role) {
        Map<String, Object> policy = map(list(map(map(resources().get(role)).get("Properties")).get("Policies")).getFirst());
        List<Map<String, Object>> statements = new ArrayList<>();
        list(map(policy.get("PolicyDocument")).get("Statement")).forEach(statement -> statements.add(map(statement)));
        return statements;
    }

    private static List<Map<String, Object>> trust(String role) {
        Map<String, Object> document = map(map(map(resources().get(role)).get("Properties")).get("AssumeRolePolicyDocument"));
        List<Map<String, Object>> statements = new ArrayList<>();
        list(document.get("Statement")).forEach(statement -> statements.add(map(statement)));
        return statements;
    }

    private static List<String> actionsOn(List<Map<String, Object>> statements, String resource) {
        return statements.stream().filter(statement -> resource.equals(statement.get("Resource")))
                .flatMap(statement -> actions(statement).stream()).toList();
    }

    private static List<String> actions(Map<String, Object> statement) {
        Object action = statement.get("Action");
        return action instanceof String single ? List.of(single) : list(action).stream().map(String::valueOf).toList();
    }

    /** {@code !GetAtt Fila.Arn} lido como texto vira {@code Fila}. */
    private static String logicalId(Object getAtt) {
        return getAtt.toString().replace(".Arn", "");
    }

    /** {@code outcome} que o passo {@code record-insurer-outcome} do {@code Catch} grava. */
    private static Object recordedOutcome(Map<String, Object> processor, Object handler) {
        Map<String, Object> state = map(states(processor).get(map(handler).get("Next")));
        Map<String, Object> payload = map(map(state.get("Parameters")).get("Payload"));
        assertThat(payload.get("step")).isEqualTo("record-insurer-outcome");
        return payload.get("outcome");
    }

    private static int functionTimeout(String function, Map<String, Object> params) {
        return (Integer) resolve(map(map(serverless.get("functions")).get(function)).get("timeout"), params);
    }

    private static Map<String, Object> environment() {
        return map(map(serverless.get("provider")).get("environment"));
    }

    private static Map<String, Object> definition() {
        return map(map(map(map(serverless.get("stepFunctions")).get("stateMachines")).get("quoteFlow")).get("definition"));
    }

    private static Map<String, Object> states(Map<String, Object> machine) {
        return map(machine.get("States"));
    }

    private static List<Map<String, Object>> tasks(Map<String, Object> machine) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Object state : states(machine).values()) {
            Map<String, Object> definition = map(state);
            if ("Task".equals(definition.get("Type"))) {
                tasks.add(definition);
            } else if ("Map".equals(definition.get("Type"))) {
                tasks.addAll(tasks(map(definition.get("ItemProcessor"))));
            }
        }
        return tasks;
    }

    private static int param(Map<String, Object> params, String name) {
        Object value = params.get(name);
        assertThat(value).as("param %s", name).isNotNull();
        return (Integer) value;
    }

    /** {@code ${param:x}} vale o parâmetro do stage; {@code "${param:x}"} dentro de texto vira texto. */
    private static Object resolve(Object value, Map<String, Object> params) {
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher matcher = PARAM.matcher(text);
        if (matcher.matches()) {
            return params.get(matcher.group(1));
        }
        return matcher.replaceAll(match -> String.valueOf(params.get(match.group(1))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    /** Lê as tags do CloudFormation ({@code !GetAtt}, {@code !Ref}, {@code !Join}, {@code !If}) como valores comuns. */
    private static final class IgnoringTagsConstructor extends SafeConstructor {

        IgnoringTagsConstructor() {
            super(new LoaderOptions());
            yamlMultiConstructors.put("!", new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    if (node instanceof ScalarNode scalar) {
                        return scalar.getValue();
                    }
                    if (node instanceof SequenceNode sequence) {
                        return constructSequence(sequence);
                    }
                    return constructMapping((MappingNode) node);
                }
            });
        }
    }
}

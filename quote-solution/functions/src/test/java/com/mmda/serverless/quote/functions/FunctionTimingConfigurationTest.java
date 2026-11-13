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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Teste de configuração (§6, §9, §12 e §13 do desenho) sobre o {@code serverless.yml} da solução: a invariante
 * de orçamento que substitui o {@code TimeoutSecondsPath}, que o Floci ignora, com as rodadas do Map de
 * seguradoras, a relação visibilidade × timeout, que a AWS valida no ESM e o Floci não, e, no M4, os alarmes,
 * cujo disparo o Floci não exercita, e o papel do {@code reconciler}.
 */
class FunctionTimingConfigurationTest {

    private static final Pattern PARAM = Pattern.compile("\\$\\{param:([A-Za-z]+)}");
    private static final List<String> LAMBDA_INVOCATION_ERRORS = List.of("Lambda.ServiceException",
            "Lambda.AWSLambdaException", "Lambda.SdkClientException", "Lambda.TooManyRequestsException");

    private static Map<String, Object> serverless;
    private static Map<String, Object> params;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        serverless = new Yaml(new IgnoringTagsConstructor())
                .load(Files.readString(Path.of("../serverless.yml")));
        params = map(map(serverless.get("params")).get("local"));
    }

    @Test
    void stepBudgetFitsTheQuoteDeadline() {
        int rounds = (int) Math.ceil((double) param("maxInsurers") / param("mapConcurrency"));
        int budget = param("enrichmentTimeoutSeconds") + rounds * param("insurerTimeoutSeconds")
                + param("stepsMarginSeconds");

        assertThat(budget).isLessThanOrEqualTo(param("quoteDeadlineSeconds"));
    }

    @Test
    void workflowTimeoutIsTheDeadlinePlusTheMargin() {
        assertThat(param("workflowTimeoutSeconds"))
                .isEqualTo(param("quoteDeadlineSeconds") + param("workflowMarginSeconds"));
        assertThat(resolve(definition().get("TimeoutSeconds"))).isEqualTo(param("workflowTimeoutSeconds"));
    }

    @Test
    void mapConcurrencyAndInsurerLimitComeFromTheParameters() {
        Map<String, Object> map = map(states(definition()).get("QuoteInsurers"));

        assertThat(resolve(map.get("MaxConcurrency"))).isEqualTo(param("mapConcurrency"));
        assertThat(environment().get("MAX_INSURERS")).isEqualTo("${param:maxInsurers}");
    }

    @Test
    void budgetCoversEveryLabInsurerSoNoSelectedInsurerIsCutByTheLimit() {
        int insurers = Insurers.all().size();
        int rounds = (int) Math.ceil((double) insurers / param("mapConcurrency"));

        assertThat(param("maxInsurers")).as("sel-1 selects alpha and beta for personal use (T01)")
                .isGreaterThanOrEqualTo(insurers);
        assertThat(param("enrichmentTimeoutSeconds") + rounds * param("insurerTimeoutSeconds")
                + param("stepsMarginSeconds")).isLessThanOrEqualTo(param("quoteDeadlineSeconds"));
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

    @Test
    void waitsUseTheStepParametersAndPassTheSameTimeoutToTheDispatch() {
        for (Map<String, Object> task : tasks(definition())) {
            String resource = (String) task.get("Resource");
            Map<String, Object> payload = map(map(task.get("Parameters")).get("Payload"));
            if (resource.endsWith("waitForTaskToken")) {
                assertThat(task.get("TimeoutSeconds")).isIn("${param:enrichmentTimeoutSeconds}",
                        "${param:insurerTimeoutSeconds}");
                assertThat(payload.get("timeoutSeconds")).isEqualTo(task.get("TimeoutSeconds"));
            } else {
                assertThat(resolve(task.get("TimeoutSeconds"))).isEqualTo(functionTimeout("workflowSteps"));
            }
        }
    }

    @Test
    void queueVisibilityCoversTheConsumerTimeoutWithRedrive() {
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
                int visibility = (Integer) resolve(properties.get("VisibilityTimeout"));
                int timeout = functionTimeout(function.getKey());
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

    @Test
    void insurerRequestDeadlineIsCappedByTheRemainingQuoteDeadline() {
        Instant acceptedAt = Instant.parse("2026-11-12T10:00:00Z");
        Instant versionDeadline = Deadlines.versionDeadline(acceptedAt, Duration.ofSeconds(param("quoteDeadlineSeconds")));
        Duration insurerWait = Duration.ofSeconds(param("insurerTimeoutSeconds"));
        Instant firstDispatch = acceptedAt.plusSeconds(param("enrichmentTimeoutSeconds"));
        int rounds = (int) Math.ceil((double) param("maxInsurers") / param("mapConcurrency"));
        Instant lastDispatch = firstDispatch.plus(insurerWait.multipliedBy(rounds - 1L));

        assertThat(Deadlines.requestDeadline(firstDispatch, insurerWait, versionDeadline))
                .isEqualTo(firstDispatch.plus(insurerWait));
        assertThat(Deadlines.requestDeadline(lastDispatch, insurerWait, versionDeadline))
                .as("the last Map round still ends before the steps margin")
                .isBeforeOrEqualTo(versionDeadline.minusSeconds(param("stepsMarginSeconds")));
        Instant lateDispatch = versionDeadline.minusSeconds(5);
        assertThat(Deadlines.requestDeadline(lateDispatch, insurerWait, versionDeadline))
                .as("a late dispatch only gets what is left of the quote deadline").isEqualTo(versionDeadline);
    }

    @Test
    void everyDeadLetterQueueAndTheStreamFailuresHaveVolumeAndAgeAlarms() {
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
            assertThat(resolve(alarms.get("ApproximateNumberOfMessagesVisible").get("Threshold")))
                    .isEqualTo(param("dlqAlarmMessages"));
            assertThat(resolve(alarms.get("ApproximateAgeOfOldestMessage").get("Threshold")))
                    .isEqualTo(param("dlqAlarmAgeSeconds"));
            assertThat(alarms.values()).allSatisfy(alarm -> assertThat(alarm.get("Statistic")).isEqualTo("Maximum"));
        }
    }

    @Test
    void outboxAgeAndExpiredTaskAlarmsWatchTheMetricsTheReconcilerEmits() {
        assertThat(environment()).containsEntry("METRICS_NAMESPACE", "${self:custom.metricsNamespace}")
                .containsEntry("STAGE", "${sls:stage}");
        Map<String, Object> outbox = alarm("${self:custom.metricsNamespace}", ReconcilerMetrics.OUTBOX_PENDING_AGE_SECONDS);
        Map<String, Object> expired = alarm("${self:custom.metricsNamespace}", ReconcilerMetrics.EXPIRED_TASKS);

        assertThat(resolve(outbox.get("Threshold"))).isEqualTo(param("outboxAlarmAgeSeconds"));
        assertThat(outbox.get("Statistic")).isEqualTo("Maximum");
        assertThat(resolve(expired.get("Threshold"))).isEqualTo(param("expiredTasksAlarmThreshold"));
        assertThat(expired.get("Statistic")).isEqualTo("Sum");
        for (Map<String, Object> alarm : List.of(outbox, expired)) {
            assertThat(map(list(alarm.get("Dimensions")).getFirst()))
                    .containsEntry("Name", ReconcilerMetrics.STAGE_DIMENSION).containsEntry("Value", "${sls:stage}");
        }
    }

    @Test
    void alarmThresholdsAndReconcilerParametersFollowTheDesign() {
        assertThat(param("dlqAlarmMessages")).isEqualTo(1);
        assertThat(param("dlqAlarmAgeSeconds")).isEqualTo(300);
        assertThat(param("outboxAlarmAgeSeconds")).isEqualTo(120);
        assertThat(param("expiredTasksAlarmThreshold")).isEqualTo(1);
        assertThat(param("dlqMaxAttempts")).isEqualTo(3);
        assertThat(param("stuckVersionMarginSeconds")).isEqualTo(120);
        assertThat(param("resumingRetryAfterSeconds")).as("the resume had its whole timeout to finish")
                .isGreaterThan(functionTimeout("resume"));
        assertThat(functionTimeout("reconciler")).as("one run fits the 1 minute schedule").isLessThanOrEqualTo(60);
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
                .containsExactlyInAnyOrder("sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes");
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

    @Test
    void issuesQueueReceivesTheProviderOperationalEventsAndTheReconcilerReprocessesItsDlq() {
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
        assertThat(functionTimeout("issueProjector")).isEqualTo(functionTimeout("resume"));
        assertThat(param("issuesVisibilityTimeoutSeconds")).isEqualTo(90);
        assertThat(param("issuesMaxReceiveCount")).isEqualTo(5);
        assertThat(statements("IssueProjectorRole").stream().flatMap(statement -> actions(statement).stream()))
                .containsExactlyInAnyOrder("dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "sqs:ReceiveMessage",
                        "sqs:DeleteMessage", "sqs:GetQueueAttributes");

        List<Map<String, Object>> reconciler = statements("ReconcilerRole");
        assertThat(actionsOn(reconciler, "IssuesDeadLetterQueue.Arn"))
                .containsExactlyInAnyOrder("sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes");
        assertThat(actionsOn(reconciler, "IssuesQueue.Arn")).containsExactly("sqs:SendMessage");
        assertThat(environment()).containsKeys("ISSUES_QUEUE_URL", "ISSUES_DLQ_URL");
        assertThat(statements("ApiRole").stream().flatMap(statement -> actions(statement).stream()))
                .as("the selection checks the offer in its transaction").contains("dynamodb:ConditionCheckItem");
    }

    private static Map<String, Object> resources() {
        return map(map(serverless.get("resources")).get("Resources"));
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

    private static int functionTimeout(String function) {
        return (Integer) resolve(map(map(serverless.get("functions")).get(function)).get("timeout"));
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

    private static int param(String name) {
        return (Integer) params.get(name);
    }

    /** {@code ${param:x}} vale o parâmetro do stage local; {@code "${param:x}"} dentro de texto vira texto. */
    private static Object resolve(Object value) {
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

    /** Lê as tags do CloudFormation ({@code !GetAtt}, {@code !Ref}, {@code !Join}) como valores comuns. */
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

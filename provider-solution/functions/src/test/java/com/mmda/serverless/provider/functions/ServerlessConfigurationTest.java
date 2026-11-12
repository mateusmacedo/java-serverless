package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
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
 * Invariantes de tempo do §9 do desenho, conferidas no serverless.yml de cada stage. O Floci aceita
 * visibilidade menor que o timeout da função e a AWS rejeita o ESM (§13), então este teste é a defesa.
 */
class ServerlessConfigurationTest {

    private static final Pattern PARAM = Pattern.compile("\\$\\{param:([A-Za-z]+)}");
    private static final List<String> WORKERS = List.of("enrichmentWorker", "insurerWorker");
    private static final List<String> QUEUE_CONSUMERS = List.of("enrichmentWorker", "insurerWorker", "simCallbackSender");

    private static Map<String, Object> serverless;
    private static Map<String, Object> quoteServerless;

    @BeforeAll
    static void load() throws IOException {
        serverless = yaml(Path.of("..", "serverless.yml"));
        quoteServerless = yaml(Path.of("..", "..", "quote-solution", "serverless.yml"));
    }

    private static Map<String, Object> yaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return new Yaml(new CloudFormationTags()).load(reader);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void workerTimeoutCoversEveryAttemptAndBackoff(String stage) {
        Map<String, Object> params = params(stage);
        int perCall = integer(params, "providerHttpTimeoutSeconds");
        int retries = integer(params, "workerRetries");
        int backoff = integer(params, "retryBackoffMaxSeconds");

        for (String worker : WORKERS) {
            assertThat(timeout(worker, params))
                    .as("timeout of %s >= providerHttpTimeoutSeconds x attempts + retryBackoffMaxSeconds", worker)
                    .isGreaterThanOrEqualTo(perCall * (retries + 1) + backoff);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void workersKnowTheirOwnTimeoutToTellALiveDuplicateFromAnInterruptedOne(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> environment = map(map(serverless.get("provider")).get("environment"));

        for (String worker : WORKERS) {
            assertThat(resolveInt(environment.get("WORKER_TIMEOUT_SECONDS"), params))
                    .as("WORKER_TIMEOUT_SECONDS seen by %s", worker)
                    .isEqualTo(timeout(worker, params));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void enrichmentLeaseOutlivesTheEnrichmentWorker(String stage) {
        Map<String, Object> params = params(stage);

        assertThat(integer(params, "enrichmentLeaseSeconds")).isGreaterThan(timeout("enrichmentWorker", params));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void enrichmentWorkerSeesALeaseLongerThanItsOwnTimeout(String stage) {
        Map<String, Object> params = params(stage);

        assertThat(resolveInt(environment().get("ENRICHMENT_LEASE_SECONDS"), params))
                .isEqualTo(integer(params, "enrichmentLeaseSeconds"))
                .isGreaterThan(timeout("enrichmentWorker", params));
    }

    /**
     * WHY: a reentrega do líder refaz o fan-out pela lápide do {@code INFLIGHT#}, então ela dura mais que todas
     * as entregas de um pedido na fila; o TTL da tabela é que a expira.
     */
    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void inflightTombstoneOutlivesEveryDeliveryOfTheLeaderAndExpiresByTheTableTtl(String stage) {
        Map<String, Object> params = params(stage);
        int tombstone = resolveInt(environment().get("INFLIGHT_TOMBSTONE_SECONDS"), params);
        Map<String, Object> queue = properties(logicalId(sqsEvent("enrichmentWorker").get("arn")));
        int visibility = resolveInt(queue.get("VisibilityTimeout"), params);
        int receives = resolveInt(map(queue.get("RedrivePolicy")).get("maxReceiveCount"), params);

        assertThat(tombstone).isEqualTo(integer(params, "inflightTombstoneSeconds"));
        assertThat(tombstone).as("tombstone TTL >= VisibilityTimeout x maxReceiveCount of enrichment-requests")
                .isGreaterThanOrEqualTo(visibility * receives)
                .isGreaterThan(integer(params, "enrichmentLeaseSeconds"));
        assertThat(properties("QueriesTable").get("TimeToLiveSpecification"))
                .isEqualTo(Map.of("AttributeName", "ttl", "Enabled", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void reusePolicyVersionIsAProviderParameterSeenByTheWorkers(String stage) {
        Object value = environment().get("REUSE_POLICY_VERSION");
        Matcher matcher = PARAM.matcher(String.valueOf(value));

        assertThat(matcher.matches()).as("REUSE_POLICY_VERSION comes from a param").isTrue();
        assertThat(params(stage).get(matcher.group(1)))
                .as("a quoted string; YAML would turn 2 or 1.0 into a number and change the published value")
                .isInstanceOfSatisfying(String.class,
                        version -> assertThat(version).matches("^[A-Za-z0-9][A-Za-z0-9._-]*$"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void everyConsumedQueueHidesMessagesLongerThanItsConsumerRunsAndHasItsOwnReceiveLimit(String stage) {
        Map<String, Object> params = params(stage);

        for (String worker : QUEUE_CONSUMERS) {
            Map<String, Object> sqs = sqsEvent(worker);
            assertThat(sqs.get("batchSize")).as("batchSize of %s", worker).isEqualTo(1);
            assertThat(sqs.get("functionResponseType")).isEqualTo("ReportBatchItemFailures");
            Map<String, Object> queue = properties(logicalId(sqs.get("arn")));
            int visibility = resolveInt(queue.get("VisibilityTimeout"), params);
            assertThat(visibility).as("VisibilityTimeout of the queue of %s", worker)
                    .isGreaterThanOrEqualTo(timeout(worker, params));
            Map<String, Object> redrive = map(queue.get("RedrivePolicy"));
            assertThat(resolveInt(redrive.get("maxReceiveCount"), params)).isPositive();
            assertThat(redrive.get("deadLetterTargetArn")).isNotNull();
        }
    }

    /** §12: a {@code sim-callbacks} esconde a mensagem por 6 vezes o timeout do consumidor e tem DLQ (§9). */
    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void simCallbacksQueueHasADeadLetterQueueAndHidesMessagesSixTimesTheSenderTimeout(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> queue = properties(logicalId(sqsEvent("simCallbackSender").get("arn")));
        Map<String, Object> redrive = map(queue.get("RedrivePolicy"));

        assertThat(logicalId(sqsEvent("simCallbackSender").get("arn"))).isEqualTo("SimCallbacksQueue");
        assertThat(timeout("simCallbackSender", params)).isEqualTo(15);
        assertThat(resolveInt(queue.get("VisibilityTimeout"), params))
                .isGreaterThanOrEqualTo(6 * timeout("simCallbackSender", params));
        assertThat(resolveInt(redrive.get("maxReceiveCount"), params)).isEqualTo(5);
        assertThat(logicalId(redrive.get("deadLetterTargetArn"))).isEqualTo("SimCallbacksDeadLetterQueue");
        assertThat(properties("SimCallbacksDeadLetterQueue").get("QueueName"))
                .isEqualTo("${self:custom.names.simCallbacksDeadLetterQueue}");
        assertThat(map(map(serverless.get("custom")).get("names")).get("simCallbacksDeadLetterQueue").toString())
                .endsWith("-sim-callbacks-dlq");
        assertThat(functionEnvironment("simulators").get("SIM_CALLBACKS_QUEUE_URL")).isEqualTo("SimCallbacksQueue");
    }

    /**
     * WHY: só o {@code vehicle-beta-late} pode chegar depois da espera da seguradora na quote; os demais
     * callbacks chegam dentro dela. A espera vem do serverless.yml da quote, no mesmo stage.
     */
    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void onlyTheLateBetaCallbackArrivesAfterTheInsurerWaitOfTheQuote(String stage) {
        Map<String, Object> params = params(stage);
        Object insurerWait = quoteParams(stage).get("insurerTimeoutSeconds");
        Assumptions.assumeTrue(insurerWait != null, "the quote declares no insurer wait for " + stage);
        int wait = Integer.parseInt(insurerWait.toString());
        Map<String, Object> simulators = functionEnvironment("simulators");

        assertThat(resolveInt(simulators.get("BETA_CALLBACK_DELAY_SECONDS"), params)).isEqualTo(
                integer(params, "betaCallbackDelaySeconds")).isLessThan(wait);
        assertThat(resolveInt(simulators.get("BETA_LATE_CALLBACK_DELAY_SECONDS"), params)).isEqualTo(
                integer(params, "betaLateCallbackDelaySeconds")).isGreaterThan(wait);
    }

    @Test
    void callbacksRouteIsServedByTheApiWhoseOwnRoleReadsTheCallbackSecret() {
        Map<String, Object> http = event("api", "http");

        assertThat(http.get("path")).isEqualTo("v1/providers/{provider}/callbacks");
        assertThat(http.get("method")).isEqualTo("post");
        assertThat(map(map(serverless.get("functions")).get("api")).get("role")).isEqualTo("CallbacksApiRole");
        assertThat(functionEnvironment("api").get("CALLBACK_SECRET_ID")).isEqualTo("${self:custom.names.callbackSecret}");
        assertThat(functionEnvironment("simCallbackSender").get("CALLBACK_SECRET_ID"))
                .isEqualTo("${self:custom.names.callbackSecret}");
        assertThat(map(map(serverless.get("functions")).get("simCallbackSender")).get("role"))
                .isEqualTo("SimCallbackSenderRole");
        assertThat(rolePolicy("CallbacksApiRole")).contains("secretsmanager:GetSecretValue=CallbackSecret");
        assertThat(rolePolicy("SimCallbackSenderRole")).contains("secretsmanager:GetSecretValue=CallbackSecret");
        assertThat(rolePolicy("SimulatorsRole")).contains("sqs:SendMessage=SimCallbacksQueue.Arn")
                .noneMatch(statement -> statement.startsWith("secretsmanager"));
        assertThat(String.valueOf(map(map(serverless.get("provider")).get("iam")).get("role")))
                .as("the workers never read the callback secret")
                .doesNotContain("CallbackSecret");
    }

    /** Menor privilégio: os papéis próprios do fluxo de callbacks têm exatamente as ações que o código usa. */
    @Test
    void callbackRolesGrantOnlyWhatTheirCodeAndTheQueueConsumerUse() {
        assertThat(rolePolicy("SimCallbackSenderRole").stream().map(statement -> statement.split("=")[0]))
                .as("the event source mapping receives and deletes; a failure returns to the queue by visibility")
                .containsExactlyInAnyOrder("logs:CreateLogStream", "logs:PutLogEvents", "sqs:ReceiveMessage",
                        "sqs:DeleteMessage", "sqs:GetQueueAttributes", "secretsmanager:GetSecretValue");
        assertThat(rolePolicy("CallbacksApiRole").stream().map(statement -> statement.split("=")[0]))
                .containsExactlyInAnyOrder("logs:CreateLogStream", "logs:PutLogEvents", "dynamodb:GetItem",
                        "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:Query", "s3:PutObject",
                        "secretsmanager:GetSecretValue");
    }

    /** T21: a quota de cada provedor é um parâmetro por stage, comum a todos os workflows. */
    @ParameterizedTest
    @ValueSource(strings = {"local", "dev"})
    void everyProviderQuotaIsAStageParameterSeenByTheWorkers(String stage) {
        Map<String, Object> params = params(stage);
        Map<String, Object> environment = environment();

        for (String variable : List.of("ENRICHMENT_SIM_QUOTA_PER_MINUTE", "ALPHA_QUOTA_PER_MINUTE",
                "BETA_QUOTA_PER_MINUTE")) {
            Matcher matcher = PARAM.matcher(String.valueOf(environment.get(variable)));
            assertThat(matcher.matches()).as("%s comes from a param", variable).isTrue();
            assertThat(integer(params, matcher.group(1))).as("%s in %s", variable, stage).isPositive();
        }
    }

    @Test
    void relayReadsOnlyOutboxInsertsWithBoundedRetriesAndPartialFailures() {
        Map<String, Object> stream = event("outboxRelay", "stream");

        assertThat(stream.get("batchSize")).isEqualTo(100);
        assertThat(stream.get("maximumRetryAttempts")).isEqualTo(3);
        assertThat(stream.get("bisectBatchOnFunctionError")).isEqualTo(true);
        assertThat(stream.get("functionResponseType")).isEqualTo("ReportBatchItemFailures");
        assertThat(logicalId(map(map(stream.get("destinations")).get("onFailure")).get("arn")))
                .isEqualTo("StreamFailuresQueue");
        assertThat(stream.get("filterPatterns")).isEqualTo(List.of(Map.of(
                "eventName", List.of("INSERT"),
                "dynamodb", Map.of("NewImage", Map.of("type", Map.of("S", List.of("OUTBOX")))))));
    }

    private static Map<String, Object> params(String stage) {
        Map<String, Object> all = map(serverless.get("params"));
        Map<String, Object> merged = new HashMap<>(map(all.get("default")));
        if (all.containsKey(stage)) {
            merged.putAll(map(all.get(stage)));
        }
        return merged;
    }

    private static Map<String, Object> environment() {
        return map(map(serverless.get("provider")).get("environment"));
    }

    private static Map<String, Object> functionEnvironment(String function) {
        return map(map(map(serverless.get("functions")).get(function)).get("environment"));
    }

    private static Map<String, Object> quoteParams(String stage) {
        Map<String, Object> all = map(quoteServerless.get("params"));
        Map<String, Object> merged = new HashMap<>();
        if (all.containsKey("default")) {
            merged.putAll(map(all.get("default")));
        }
        if (all.containsKey(stage)) {
            merged.putAll(map(all.get(stage)));
        }
        return merged;
    }

    /** Cada ação da política inline do papel, como {@code acao=recurso}. */
    @SuppressWarnings("unchecked")
    private static List<String> rolePolicy(String role) {
        List<Object> policies = (List<Object>) properties(role).get("Policies");
        return policies.stream()
                .map(policy -> map(map(policy).get("PolicyDocument")))
                .flatMap(document -> ((List<Object>) document.get("Statement")).stream())
                .map(ServerlessConfigurationTest::map)
                .flatMap(statement -> {
                    Object actions = statement.get("Action");
                    List<Object> all = actions instanceof List<?> list ? (List<Object>) list : List.of(actions);
                    return all.stream().map(action -> action + "=" + statement.get("Resource"));
                })
                .toList();
    }

    private static int timeout(String function, Map<String, Object> params) {
        return resolveInt(map(map(serverless.get("functions")).get(function)).get("timeout"), params);
    }

    private static Map<String, Object> sqsEvent(String function) {
        return event(function, "sqs");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> event(String function, String type) {
        List<Object> events = (List<Object>) map(map(serverless.get("functions")).get(function)).get("events");
        return events.stream()
                .map(ServerlessConfigurationTest::map)
                .filter(event -> event.containsKey(type))
                .map(event -> map(event.get(type)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(function + " has no " + type + " event"));
    }

    private static Map<String, Object> properties(String logicalId) {
        return map(map(map(map(serverless.get("resources")).get("Resources")).get(logicalId)).get("Properties"));
    }

    /** {@code !GetAtt Queue.Arn} chega como o texto {@code Queue.Arn}. */
    private static String logicalId(Object getAtt) {
        return getAtt.toString().split("\\.")[0];
    }

    private static int integer(Map<String, Object> params, String name) {
        Object value = params.get(name);
        assertThat(value).as("param %s", name).isNotNull();
        return Integer.parseInt(value.toString());
    }

    private static int resolveInt(Object value, Map<String, Object> params) {
        Matcher matcher = PARAM.matcher(value.toString());
        return matcher.matches() ? integer(params, matcher.group(1)) : Integer.parseInt(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /** Lê as tags curtas do CloudFormation ({@code !Ref}, {@code !GetAtt}, {@code !Join}) como valores comuns. */
    private static final class CloudFormationTags extends SafeConstructor {

        CloudFormationTags() {
            super(new LoaderOptions());
            this.yamlConstructors.put(null, new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    return switch (node) {
                        case ScalarNode scalar -> constructScalar(scalar);
                        case SequenceNode sequence -> constructSequence(sequence);
                        case MappingNode mapping -> constructMapping(mapping);
                        default -> null;
                    };
                }
            });
        }
    }
}

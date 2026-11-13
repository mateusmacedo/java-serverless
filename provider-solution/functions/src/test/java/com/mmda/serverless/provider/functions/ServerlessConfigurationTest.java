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

    private static Map<String, Object> serverless;

    @BeforeAll
    static void load() throws IOException {
        try (Reader reader = Files.newBufferedReader(Path.of("..", "serverless.yml"))) {
            serverless = new Yaml(new CloudFormationTags()).load(reader);
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
    void everyConsumedQueueHidesMessagesLongerThanItsConsumerRunsAndHasItsOwnReceiveLimit(String stage) {
        Map<String, Object> params = params(stage);

        for (String worker : WORKERS) {
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

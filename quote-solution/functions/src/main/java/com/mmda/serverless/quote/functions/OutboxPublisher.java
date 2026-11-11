package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ExecutionAlreadyExistsException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

/**
 * Publica um item de outbox: {@code StartQuoteRequested} inicia a execução com o nome determinístico; os
 * demais vão para o barramento da plataforma. É o mesmo código na {@code outbox-relay} e no
 * {@code reconciler} (§7 do desenho). Publicar de novo não tem efeito: o {@code eventId} vai no
 * {@code detail}, a execução repetida conta como sucesso e o {@code publishedAt} é gravado com {@code SET}.
 */
@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    /** Resultado por item, que decide entre seguir, reentregar o registro ou só registrar a falha. */
    public enum Result {
        PUBLISHED,
        /** Falha que passa sozinha: o registro volta pelo stream ou pelo próximo ciclo do reconciler. */
        TRANSIENT_FAILURE,
        /** Falha que não passa repetindo: grava {@code attempts} e {@code lastError}; o item segue pendente. */
        PERMANENT_FAILURE
    }

    /** Item de outbox lido da tabela ou da imagem do stream. */
    public record OutboxItem(String eventId, String detailType, String detail) {

        static OutboxItem from(Map<String, AttributeValue> item) {
            return new OutboxItem(Attr.string(item, "eventId"), Attr.string(item, "detailType"),
                    Attr.string(item, "detail"));
        }
    }

    private final QuoteTable table;
    private final EventBridgeClient eventBridge;
    private final SfnClient sfn;
    private final ObjectMapper objectMapper;
    private final QuoteFunctionsConfig config;
    private final Clock clock;

    OutboxPublisher(QuoteTable table, EventBridgeClient eventBridge, SfnClient sfn, ObjectMapper objectMapper,
            QuoteFunctionsConfig config, Clock clock) {
        this.table = table;
        this.eventBridge = eventBridge;
        this.sfn = sfn;
        this.objectMapper = objectMapper;
        this.config = config;
        this.clock = clock;
    }

    public Result publish(OutboxItem item) {
        try {
            if (QuoteEvents.START_QUOTE_REQUESTED.equals(item.detailType())) {
                startExecution(item);
            } else {
                putEvent(item);
            }
            markPublished(item);
            return Result.PUBLISHED;
        } catch (TransientPublishFailure | SdkClientException failure) {
            LOG.warnf("outbox %s (%s) will be retried: %s", item.eventId(), item.detailType(), failure.getMessage());
            return Result.TRANSIENT_FAILURE;
        } catch (AwsServiceException failure) {
            if (failure.isThrottlingException() || failure.statusCode() >= 500) {
                LOG.warnf("outbox %s (%s) will be retried: %s", item.eventId(), item.detailType(), failure.getMessage());
                return Result.TRANSIENT_FAILURE;
            }
            recordFailure(item, failure.awsErrorDetails() == null ? failure.getMessage()
                    : failure.awsErrorDetails().errorCode());
            return Result.PERMANENT_FAILURE;
        } catch (PermanentPublishFailure | JsonProcessingException | IllegalArgumentException failure) {
            recordFailure(item, failure.getMessage());
            return Result.PERMANENT_FAILURE;
        }
    }

    private void startExecution(OutboxItem item) throws JsonProcessingException {
        JsonNode detail = objectMapper.readTree(item.detail());
        String quoteId = required(detail, "quoteId");
        int version = detail.path("version").asInt();
        String executionName = QuoteKeys.executionName(quoteId, version);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("quoteId", quoteId);
        input.put("version", version);
        String executionArn;
        try {
            executionArn = sfn.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(config.stateMachineArn())
                    .name(executionName)
                    .input(objectMapper.writeValueAsString(input))
                    .build()).executionArn();
        } catch (ExecutionAlreadyExistsException alreadyStarted) {
            // WHY: nome e entrada saem do mesmo par quoteId/version, então a execução existente é esta mesma
            executionArn = executionArn(executionName);
        }
        Instant now = Instant.now(clock);
        table.updateIf(table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version))
                .updateExpression("SET executionArn = :arn, executionName = :name")
                .conditionExpression("attribute_exists(" + QuoteKeys.PK + ")")
                .expressionAttributeValues(Map.of(":arn", s(executionArn), ":name", s(executionName)))
                .build());
        LOG.infof("execution %s started for quoteId=%s version=%d at %s", executionName, quoteId, version, now);
    }

    private void putEvent(OutboxItem item) {
        PutEventsResultEntry entry = eventBridge.putEvents(PutEventsRequest.builder()
                .entries(PutEventsRequestEntry.builder()
                        .eventBusName(config.eventBusName())
                        .source(QuoteEvents.SOURCE)
                        .detailType(item.detailType())
                        .detail(item.detail())
                        .build())
                .build()).entries().getFirst();
        if (entry.errorCode() != null) {
            if ("InternalFailure".equals(entry.errorCode()) || "ThrottlingException".equals(entry.errorCode())) {
                throw new TransientPublishFailure(entry.errorCode());
            }
            throw new PermanentPublishFailure(entry.errorCode() + ": " + entry.errorMessage());
        }
    }

    private void markPublished(OutboxItem item) {
        table.updateIf(table.update(QuoteKeys.outboxPk(item.eventId()), QuoteKeys.META)
                .updateExpression("SET publishedAt = :now REMOVE " + QuoteKeys.GSI2_PK + ", " + QuoteKeys.GSI2_SK)
                .conditionExpression("attribute_exists(" + QuoteKeys.PK + ")")
                .expressionAttributeValues(Map.of(":now", s(Instant.now(clock).toString())))
                .build());
    }

    private void recordFailure(OutboxItem item, String error) {
        LOG.errorf("outbox %s (%s) failed permanently: %s", item.eventId(), item.detailType(), error);
        if (item.eventId() == null) {
            return;
        }
        // WHY: o reconciler lê os pendentes mais antigos pelo gsi2sk; com a data da última falha, um item que
        // falha sempre vai para o fim da fila e não impede a republicação dos mais novos
        table.updateIf(table.update(QuoteKeys.outboxPk(item.eventId()), QuoteKeys.META)
                .updateExpression("SET attempts = if_not_exists(attempts, :zero) + :one, lastError = :error, "
                        + QuoteKeys.GSI2_SK + " = :now")
                .conditionExpression("attribute_exists(" + QuoteKeys.GSI2_PK + ")")
                .expressionAttributeValues(Map.of(":zero", n(0), ":one", n(1),
                        ":error", s(error == null ? "unknown" : error), ":now", s(Instant.now(clock).toString())))
                .build());
    }

    /** ARN derivável do nome da máquina e da execução (§7 do desenho). */
    String executionArn(String executionName) {
        return config.stateMachineArn().replace(":stateMachine:", ":execution:") + ":" + executionName;
    }

    private static String required(JsonNode detail, String field) {
        JsonNode value = detail.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("outbox detail without " + field);
        }
        return value.asText();
    }

    static final class TransientPublishFailure extends RuntimeException {

        TransientPublishFailure(String message) {
            super(message);
        }
    }

    static final class PermanentPublishFailure extends RuntimeException {

        PermanentPublishFailure(String message) {
            super(message);
        }
    }
}

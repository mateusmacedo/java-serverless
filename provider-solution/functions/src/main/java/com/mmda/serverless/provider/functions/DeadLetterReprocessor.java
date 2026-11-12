package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.Identities;
import com.mmda.serverless.provider.domain.Timestamps;
import com.mmda.serverless.provider.domain.operations.DeadLetterPolicy;
import com.mmda.serverless.provider.domain.operations.IssueType;
import com.mmda.serverless.provider.domain.operations.QueryReconcileRequest;
import com.mmda.serverless.provider.domain.operations.ReconcileStatus;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Reprocessamento das DLQs da provider no padrão do ADR 0007 (desenho, §9): abaixo de {@code dlqMaxAttempts} e
 * antes do {@code deadlineAt} do pedido, a mensagem volta à fila de origem com a tentativa seguinte no atributo
 * {@code dlqAttempt}; depois, é apagada com uma ocorrência {@code DEAD_LETTER}. INVARIANT: o reenvio não duplica
 * resultado nem chamada (T31), porque o worker relê a consulta pelo {@code workRef} e o pedido já atendido não faz
 * nada.
 */
@Component
class DeadLetterReprocessor {

    static final String ATTEMPT_ATTRIBUTE = "dlqAttempt";
    private static final String RECONCILE_REQUESTS = "reconcile-requests";
    private static final int MAX_MESSAGES = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterReprocessor.class);

    private final SqsClient sqs;
    private final DeadLetterPolicy policy;
    private final OperationIssues issues;
    private final OperationLedger operationLedger;
    private final QueryLedger ledger;
    private final EventJson json;
    private final OperationsProperties operations;

    DeadLetterReprocessor(SqsClient sqs, DeadLetterPolicy policy, OperationIssues issues,
                          OperationLedger operationLedger, QueryLedger ledger, EventJson json,
                          OperationsProperties operations) {
        this.sqs = sqs;
        this.policy = policy;
        this.issues = issues;
        this.operationLedger = operationLedger;
        this.ledger = ledger;
        this.json = json;
        this.operations = operations;
    }

    record Report(int redriven, int discarded) {
    }

    Report run(Instant now) {
        int redriven = 0;
        int discarded = 0;
        for (OperationsProperties.DeadLetterRoute route : operations.deadLetterQueues()) {
            if (!route.configured()) {
                continue;
            }
            for (Message message : receive(route)) {
                try {
                    if (reprocess(route, message, now)) {
                        redriven++;
                    } else {
                        discarded++;
                    }
                } catch (RuntimeException exception) {
                    LOGGER.error("Dead letter not reprocessed, it returns to the DLQ: queue={} messageId={}",
                            route.name(), message.messageId(), exception);
                }
            }
        }
        return new Report(redriven, discarded);
    }

    private Iterable<Message> receive(OperationsProperties.DeadLetterRoute route) {
        return sqs.receiveMessage(builder -> builder
                        .queueUrl(route.deadLetterQueueUrl())
                        .maxNumberOfMessages(MAX_MESSAGES)
                        .waitTimeSeconds(0)
                        .messageAttributeNames(ATTEMPT_ATTRIBUTE))
                .messages();
    }

    /** {@code true} quando a mensagem voltou à fila de origem; {@code false} quando foi descartada. */
    private boolean reprocess(OperationsProperties.DeadLetterRoute route, Message message, Instant now) {
        int attempt = attempt(message);
        Payload payload = Payload.of(json.readOrNull(message.body(), Map.class), message.messageId());
        DeadLetterPolicy.Decision decision = payload == null
                ? new DeadLetterPolicy.Discard(DeadLetterPolicy.Reason.UNREADABLE)
                : policy.decide(attempt, payload.deadlineAt(), now);
        if (decision instanceof DeadLetterPolicy.Redrive redrive) {
            sqs.sendMessage(builder -> builder
                    .queueUrl(route.queueUrl())
                    .messageBody(message.body())
                    .messageAttributes(Map.of(ATTEMPT_ATTRIBUTE, MessageAttributeValue.builder()
                            .dataType("Number")
                            .stringValue(Integer.toString(redrive.nextAttempt()))
                            .build())));
            delete(route, message);
            LOGGER.warn("Dead letter redriven to its queue: queue={} attempt={} target={}", route.name(),
                    redrive.nextAttempt(), payload.key());
            return true;
        }
        DeadLetterPolicy.Reason reason = ((DeadLetterPolicy.Discard) decision).reason();
        Payload described = payload == null ? Payload.unreadable(message.messageId()) : payload;
        Payload scoped = scoped(route, described);
        issues.raise(new OperationIssues.Observation(IssueType.DEAD_LETTER, route.name() + "|" + described.key(),
                described.organizationId(), scoped.queryId(), scoped.quoteId(), summary(route, reason, attempt)), true);
        if (payload != null && RECONCILE_REQUESTS.equals(route.name())) {
            finishDiscardedReconciliation(message, now);
        }
        delete(route, message);
        LOGGER.error("Dead letter discarded: queue={} reason={} attempt={} target={}", route.name(), reason, attempt,
                described.key());
        return false;
    }

    /**
     * A reconciliação manual descartada termina {@code FAILED}, com o {@code QueryReconciled}; sem isso, o
     * {@code OPERATION#} ficaria {@code RUNNING} para sempre. Só muda a operação que ainda está {@code RUNNING}.
     */
    private void finishDiscardedReconciliation(Message message, Instant now) {
        try {
            QueryReconcileRequest request = json.detail(message.body(), QueryReconcileRequest.class);
            boolean finished = operationLedger.finish(request, ReconcileStatus.FAILED,
                    "Reconciliação descartada depois de falhar repetidamente", now);
            LOGGER.warn("Discarded reconciliation finished: operationId={} recorded={}", request.operationId(),
                    finished);
        } catch (RuntimeException unusable) {
            LOGGER.error("Discarded reconciliation without a usable request: messageId={}", message.messageId(),
                    unusable);
        }
    }

    /**
     * WHY: o par {@code queryId} e {@code organizationId} da ocorrência autoriza a reconciliação manual na quote
     * ({@code QUERYREF#}). Fica fora dela a consulta de outra organização e, no pedido de reconciliação, a que não
     * existe: o {@code reconcileWorker} trata as duas como inexistentes. No pedido de consulta, a consulta derivada do
     * {@code workRef} é do próprio pedido, mesmo antes de existir. A cotação que a mensagem não traz, como no pedido
     * de reconciliação, vem da {@code QUERY} da própria organização.
     */
    private Payload scoped(OperationsProperties.DeadLetterRoute route, Payload payload) {
        if (payload.queryId() == null || payload.organizationId() == null) {
            return payload;
        }
        Optional<QueryLedger.QueryOwner> owner = ledger.ownerOf(payload.queryId());
        if (owner.isEmpty()) {
            return RECONCILE_REQUESTS.equals(route.name()) ? payload.withoutQuery() : payload;
        }
        if (!owner.get().organizationId().equals(payload.organizationId())) {
            return payload.withoutQuery();
        }
        return payload.quoteId() == null ? payload.withQuote(owner.get().quoteId()) : payload;
    }

    private void delete(OperationsProperties.DeadLetterRoute route, Message message) {
        sqs.deleteMessage(builder -> builder
                .queueUrl(route.deadLetterQueueUrl())
                .receiptHandle(message.receiptHandle()));
    }

    private static int attempt(Message message) {
        MessageAttributeValue value = message.messageAttributes().get(ATTEMPT_ATTRIBUTE);
        if (value == null || value.stringValue() == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.stringValue()));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static String summary(OperationsProperties.DeadLetterRoute route, DeadLetterPolicy.Reason reason,
                                  int attempt) {
        return switch (reason) {
            case MAX_ATTEMPTS -> "Mensagem de %s descartada depois de %d tentativas".formatted(route.name(), attempt);
            case DEADLINE_EXCEEDED -> "Mensagem de %s descartada depois do prazo do pedido".formatted(route.name());
            case UNREADABLE -> "Mensagem ilegível de %s descartada".formatted(route.name());
        };
    }

    /**
     * O que a mensagem diz do pedido: o envelope do EventBridge, nos pedidos, ou o callback agendado da beta. A
     * {@code key} identifica o pedido lógico: o {@code queryId} derivado do {@code workRef}, o {@code operationId}
     * ou o {@code externalEventId}.
     */
    private record Payload(String key, String organizationId, String queryId, String quoteId, Instant deadlineAt) {

        static Payload of(Map<?, ?> body, String messageId) {
            if (body == null) {
                return null;
            }
            Map<?, ?> detail = body.get("detail") instanceof Map<?, ?> nested ? nested : body;
            String workRef = text(detail, "workRef");
            String queryId = workRef != null ? Identities.queryId(workRef) : text(detail, "queryId");
            String key = workRef != null ? queryId
                    : firstNonNull(text(detail, "operationId"), text(detail, "externalEventId"), messageId);
            return new Payload(key, text(detail, "organizationId"), queryId, text(detail, "quoteId"),
                    instant(text(detail, "deadlineAt")));
        }

        static Payload unreadable(String messageId) {
            return new Payload(messageId, null, null, null, null);
        }

        Payload withoutQuery() {
            return new Payload(key, organizationId, null, null, deadlineAt);
        }

        Payload withQuote(String ownerQuoteId) {
            return new Payload(key, organizationId, queryId, ownerQuoteId, deadlineAt);
        }

        private static String text(Map<?, ?> values, String name) {
            return values.get(name) instanceof String value ? value : null;
        }

        private static Instant instant(String value) {
            try {
                return value == null ? null : Timestamps.parse(value);
            } catch (DateTimeParseException exception) {
                return null;
            }
        }

        private static String firstNonNull(String... values) {
            for (String value : values) {
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
    }
}

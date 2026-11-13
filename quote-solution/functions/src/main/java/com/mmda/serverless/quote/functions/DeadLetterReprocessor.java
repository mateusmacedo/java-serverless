package com.mmda.serverless.quote.functions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.Reconciliation;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Reprocessamento da {@code results-dlq} e, desde o M5, da {@code issues-dlq}, no padrão do ADR 0007 (§9 do
 * desenho, T31): abaixo do limite de tentativas, a mensagem volta à fila de origem, mesmo depois do prazo da versão;
 * no limite, ou sem identificar o recurso, vira {@code DEAD_LETTER}. Os consumidores são idempotentes pelo
 * {@code CONSUMED#<eventId>}, então o reenvio não duplica efeito.
 */
@ApplicationScoped
public class DeadLetterReprocessor {

    /** Atributo da mensagem com o número da tentativa; ausente na entrega original, que é a tentativa 1. */
    static final String ATTEMPT_ATTRIBUTE = "dlqAttempt";
    private static final Logger LOG = Logger.getLogger(DeadLetterReprocessor.class);
    /** WHY: cobre o processamento de um lote; se o reconciler cair, a mensagem volta à DLQ para o ciclo seguinte. */
    private static final int RECEIVE_VISIBILITY_SECONDS = 60;
    private static final int MAX_MESSAGES_PER_RECEIVE = 10;

    private final SqsClient sqs;
    private final QuoteTable table;
    private final ObjectMapper objectMapper;
    private final QuoteFunctionsConfig config;
    private final ReconcilerConfig reconcilerConfig;

    DeadLetterReprocessor(SqsClient sqs, QuoteTable table, ObjectMapper objectMapper, QuoteFunctionsConfig config,
            ReconcilerConfig reconcilerConfig) {
        this.sqs = sqs;
        this.table = table;
        this.objectMapper = objectMapper;
        this.config = config;
        this.reconcilerConfig = reconcilerConfig;
    }

    /** {@code results-dlq}; contadores {@code redriven} e {@code discarded}. */
    Map<String, Integer> reprocess(Instant now) {
        return reprocess(new Source("results-dlq", reconcilerConfig.resultsDeadLetterQueueUrl(),
                reconcilerConfig.resultsQueueUrl(), false), now);
    }

    /** {@code issues-dlq} (M5); contadores {@code redriven} e {@code discarded}. */
    Map<String, Integer> reprocessIssues(Instant now) {
        return reprocess(new Source("issues-dlq", reconcilerConfig.issuesDeadLetterQueueUrl(),
                reconcilerConfig.issuesQueueUrl(), true), now);
    }

    /** DLQ, fila de origem e o que identifica o recurso das mensagens dela. */
    private record Source(String name, String deadLetterQueueUrl, String queueUrl, boolean issues) {
    }

    private Map<String, Integer> reprocess(Source source, Instant now) {
        Map<String, Integer> report = new LinkedHashMap<>(Map.of("redriven", 0, "discarded", 0));
        int budget = config.reconcilerBatchSize();
        while (budget > 0) {
            int batch = Math.min(budget, MAX_MESSAGES_PER_RECEIVE);
            List<Message> messages = sqs.receiveMessage(builder -> builder
                    .queueUrl(source.deadLetterQueueUrl())
                    .maxNumberOfMessages(batch)
                    .waitTimeSeconds(0)
                    .visibilityTimeout(RECEIVE_VISIBILITY_SECONDS)
                    .messageAttributeNames("All")).messages();
            if (messages.isEmpty()) {
                break;
            }
            for (Message message : messages) {
                report.merge(handle(source, message, now) == Reconciliation.DeadLetterDecision.REDRIVE
                        ? "redriven" : "discarded", 1, Integer::sum);
            }
            budget -= messages.size();
        }
        return report;
    }

    private Reconciliation.DeadLetterDecision handle(Source source, Message message, Instant now) {
        int attempt = attempt(message);
        MessageRef ref = MessageRef.of(read(message.body()), message.messageId());
        boolean identified = source.issues() ? ref.identifiesIssueEvent() : ref.identifiesQuote();
        Reconciliation.DeadLetterDecision decision =
                Reconciliation.deadLetter(attempt, reconcilerConfig.dlqMaxAttempts(), identified);
        if (decision == Reconciliation.DeadLetterDecision.REDRIVE) {
            // INVARIANT: reenvia antes de apagar; uma queda no meio repete o reenvio, que o consumidor descarta
            sqs.sendMessage(builder -> builder
                    .queueUrl(source.queueUrl())
                    .messageBody(message.body())
                    .messageAttributes(Map.of(ATTEMPT_ATTRIBUTE, MessageAttributeValue.builder()
                            .dataType("Number").stringValue(Integer.toString(attempt + 1)).build())));
            LOG.infof("dead letter %s (%s) redriven as attempt %d from %s", ref.target(), ref.detailType(),
                    attempt + 1, source.name());
        } else {
            table.writeIssue(source.issues() ? issueEventDeadLetter(ref, attempt, identified)
                    : resultDeadLetter(ref, attempt, identified), now);
            LOG.warnf("dead letter %s (%s) discarded at attempt %d as DEAD_LETTER from %s", ref.target(),
                    ref.detailType(), attempt, source.name());
        }
        sqs.deleteMessage(builder -> builder.queueUrl(source.deadLetterQueueUrl())
                .receiptHandle(message.receiptHandle()));
        return decision;
    }

    private QuoteIssue resultDeadLetter(MessageRef ref, int attempt, boolean identified) {
        String reason = identified ? "no limite de " + reconcilerConfig.dlqMaxAttempts() + " tentativas"
                : "sem identificar a cotação";
        return new QuoteIssue(QuoteIssues.Type.DEAD_LETTER, ref.target(),
                ref.quoteId() == null ? null : table.organizationOf(ref.quoteId()), ref.quoteId(),
                ref.version() < 1 ? null : ref.version(), ref.queryId(),
                "Resultado " + ref.detailType() + " descartado da results-dlq na tentativa " + attempt + ", "
                        + reason + ".");
    }

    /**
     * Evento operacional da provider descartado. WHY: a organização e a consulta vêm do próprio evento; numa
     * ocorrência, a provider só põe nelas a organização da consulta, mas no {@code QueryReconciled} o par é o do
     * pedido. A ocorrência da quote não grava {@code QUERYREF#} nem é {@code reconcilable}, então o par só a situa na
     * listagem; sem organização, ela fica fora da listagem, como o callback sem correlação.
     */
    private QuoteIssue issueEventDeadLetter(MessageRef ref, int attempt, boolean identified) {
        String reason = identified ? "no limite de " + reconcilerConfig.dlqMaxAttempts() + " tentativas"
                : "sem identificar a ocorrência ou a reconciliação";
        return new QuoteIssue(QuoteIssues.Type.DEAD_LETTER, ref.target(), ref.organizationId(), ref.quoteId(), null,
                ref.queryId(), "Evento " + ref.detailType() + " descartado da issues-dlq na tentativa " + attempt
                        + ", " + reason + ".");
    }

    private static int attempt(Message message) {
        MessageAttributeValue value = message.messageAttributes().get(ATTEMPT_ATTRIBUTE);
        if (value == null || value.stringValue() == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.stringValue()));
        } catch (NumberFormatException malformed) {
            return 1;
        }
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (IOException unreadable) {
            return objectMapper.missingNode();
        }
    }

    /**
     * O que a ocorrência guarda da mensagem, sem o payload: o alvo é o {@code eventId} do evento ou, numa mensagem
     * ilegível, o {@code messageId} do SQS.
     */
    private record MessageRef(String target, String eventId, String detailType, String quoteId, int version,
            String queryId, String organizationId, String issueId, String operationId) {

        static MessageRef of(JsonNode envelope, String messageId) {
            JsonNode detail = envelope.path("detail");
            String eventId = text(detail, "eventId");
            String detailType = text(envelope, "detail-type");
            return new MessageRef(eventId != null ? eventId : messageId, eventId,
                    detailType != null ? detailType : "ilegível", text(detail, "quoteId"),
                    detail.path("version").asInt(0), text(detail, "queryId"), text(detail, "organizationId"),
                    text(detail, "issueId"), text(detail, "operationId"));
        }

        boolean identifiesQuote() {
            return quoteId != null && version >= 1;
        }

        boolean identifiesIssueEvent() {
            return eventId != null && (issueId != null || operationId != null);
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || !value.isTextual() ? null : value.asText();
        }
    }
}

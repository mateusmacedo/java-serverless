package com.mmda.serverless.quote.functions;

import static com.mmda.serverless.quote.functions.Attr.n;
import static com.mmda.serverless.quote.functions.Attr.s;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.Consolidation;
import com.mmda.serverless.quote.domain.Consolidation.ConsolidationResult;
import com.mmda.serverless.quote.domain.Consolidation.InsurerResultView;
import com.mmda.serverless.quote.domain.EligibilityDecision;
import com.mmda.serverless.quote.domain.EligibilityResult;
import com.mmda.serverless.quote.domain.EligibilityRule;
import com.mmda.serverless.quote.domain.EnrichmentOutcome;
import com.mmda.serverless.quote.domain.InsurerSelectionRule;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.Insurers;
import com.mmda.serverless.quote.domain.MissingField;
import com.mmda.serverless.quote.domain.OutcomeReason;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteEvents;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import com.mmda.serverless.quote.domain.WorkRef;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Passos simples da máquina (§6 do desenho). Cada passo é idempotente: escreve com condição e, numa nova
 * tentativa, relê e devolve o que já gravou. Depois da escrita, atualiza o resumo do {@code META}.
 */
@ApplicationScoped
public class WorkflowSteps {

    static final String TIMEOUT_REASON = "STEP_TIMEOUT";
    static final String TASK_FAILED_REASON = "TASK_FAILED";
    private static final int CONSOLIDATION_ATTEMPTS = 3;
    private static final TypeReference<List<MissingField>> MISSING_FIELDS = new TypeReference<>() {
    };

    private final QuoteTable table;
    private final ObjectMapper objectMapper;
    private final QuoteFunctionsConfig config;
    private final Clock clock;

    WorkflowSteps(QuoteTable table, ObjectMapper objectMapper, QuoteFunctionsConfig config, Clock clock) {
        this.table = table;
        this.objectMapper = objectMapper;
        this.config = config;
        this.clock = clock;
    }

    /** {@code load-snapshot}: fase {@code VALIDATING}; devolve organização e prazo da versão. */
    public Map<String, Object> loadSnapshot(String quoteId, int version) {
        Map<String, AttributeValue> versionItem = versionItem(quoteId, version);
        Map<String, AttributeValue> meta = table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.META)
                .orElseThrow(() -> new IllegalStateException("quote " + quoteId + " has no META"));
        Instant now = Instant.now(clock);
        if (Phase.RECEIVED.name().equals(Attr.string(versionItem, "phase"))) {
            table.updateIf(table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version))
                    .updateExpression("SET phase = :validating, revision = revision + :one, updatedAt = :now")
                    .conditionExpression("phase = :received")
                    .expressionAttributeValues(Map.of(":validating", s(Phase.VALIDATING.name()), ":one", n(1),
                            ":now", s(now.toString()), ":received", s(Phase.RECEIVED.name())))
                    .build());
            table.refreshSummary(quoteId, version, now);
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("organizationId", Attr.string(meta, "organizationId"));
        context.put("deadlineAt", Attr.string(versionItem, "deadlineAt"));
        return context;
    }

    /** {@code evaluate-eligibility}: aplica {@code elig-1} uma vez e grava a decisão com a versão da regra. */
    public Map<String, Object> evaluateEligibility(String quoteId, int version) {
        Map<String, AttributeValue> versionItem = versionItem(quoteId, version);
        EligibilityResult stored = storedEligibility(versionItem);
        if (stored != null) {
            return eligibilityOutput(stored);
        }
        EligibilityResult result = EligibilityRule.evaluate(snapshot(versionItem));
        Instant now = Instant.now(clock);
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":decision", s(result.decision().name()),
                ":missing", s(table.toJson(result.missingFields())),
                ":rule", s(result.ruleVersion()),
                ":one", n(1),
                ":now", s(now.toString())));
        StringBuilder expression = new StringBuilder("SET eligibilityDecision = :decision, "
                + "eligibilityMissingFields = :missing, eligibilityRuleVersion = :rule, revision = revision + :one, "
                + "updatedAt = :now");
        if (result.reasonCode() != null) {
            values.put(":reason", s(result.reasonCode().name()));
            expression.append(", eligibilityReasonCode = :reason");
        }
        if (result.decision() == EligibilityDecision.ELIGIBLE) {
            values.put(":enriching", s(Phase.ENRICHING.name()));
            expression.append(", phase = :enriching");
        }
        boolean written = table.updateIf(table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version))
                .updateExpression(expression.toString())
                .conditionExpression("attribute_not_exists(eligibilityRuleVersion)")
                .expressionAttributeValues(values)
                .build());
        if (!written) {
            return eligibilityOutput(storedEligibility(versionItem(quoteId, version)));
        }
        table.refreshSummary(quoteId, version, now);
        return eligibilityOutput(result);
    }

    /**
     * {@code select-insurers}: aplica {@code sel-1} com o ano do veículo do enriquecimento e grava, numa
     * transação, a fase {@code QUOTING} e um {@code RESULT} {@code PENDING} por seguradora. Sem evidência
     * {@code FOUND}, devolve a lista vazia sem gravar: nenhuma seguradora é consultada (PRD §8), e a
     * consolidação decide pela evidência.
     */
    public Map<String, Object> selectInsurers(String quoteId, int version) {
        Map<String, AttributeValue> versionItem = versionItem(quoteId, version);
        List<String> stored = Attr.strings(versionItem, "selectedInsurers");
        if (stored != null) {
            return selectionOutput(stored);
        }
        Optional<Map<String, AttributeValue>> evidence = enrichmentEvidence(quoteId, version)
                .filter(item -> EnrichmentOutcome.ResultType.FOUND.name().equals(Attr.string(item, "resultType")));
        if (evidence.isEmpty()) {
            return selectionOutput(List.of());
        }
        InsurerSelectionRule.InsurerSelection selection = InsurerSelectionRule.select(snapshot(versionItem),
                readTree(Attr.string(evidence.get(), "summary")).path("result"), config.maxInsurers());
        Instant now = Instant.now(clock);
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":insurers", Attr.list(selection.insurerIds()),
                ":rule", s(selection.ruleVersion()),
                ":one", n(1),
                ":now", s(now.toString())));
        String expression = "SET selectedInsurers = :insurers, selectionRuleVersion = :rule, "
                + "revision = revision + :one, updatedAt = :now";
        if (!selection.insurerIds().isEmpty()) {
            values.put(":quoting", s(Phase.QUOTING.name()));
            expression += ", phase = :quoting";
        }
        List<TransactWriteItem> writes = new ArrayList<>();
        writes.add(table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version), expression,
                "attribute_not_exists(selectionRuleVersion)", Map.of(), values));
        for (String insurerId : selection.insurerIds()) {
            Map<String, AttributeValue> result = new HashMap<>();
            result.put(QuoteKeys.PK, s(QuoteKeys.quotePk(quoteId)));
            result.put(QuoteKeys.SK, s(QuoteKeys.resultSk(version, insurerId)));
            result.put(QuoteKeys.TYPE, s(QuoteKeys.TYPE_RESULT));
            result.put("quoteId", s(quoteId));
            result.put("version", n(version));
            result.put("insurerId", s(insurerId));
            result.put("insurerName", s(Insurers.nameOf(insurerId).orElse(insurerId)));
            result.put("status", s(InsurerStatus.PENDING.name()));
            result.put("revision", n(1));
            result.put("updatedAt", s(now.toString()));
            writes.add(table.put(result, QuoteTable.NOT_EXISTS));
        }
        try {
            table.transact(writes);
        } catch (TransactionCanceledException alreadySelected) {
            List<String> selected = Attr.strings(versionItem(quoteId, version), "selectedInsurers");
            if (selected == null) {
                throw alreadySelected;
            }
            return selectionOutput(selected);
        }
        table.refreshSummary(quoteId, version, now);
        return selectionOutput(selection.insurerIds());
    }

    /**
     * {@code record-insurer-outcome}: o {@code Catch} da espera registra {@code TIMED_OUT} ou
     * {@code UNAVAILABLE}. WHY: o waiter é marcado {@code EXPIRED} antes do {@code RESULT}; se um resultado
     * chegou no mesmo instante, a transação do {@code resume} ou esta condição perde, nunca as duas.
     */
    public Map<String, Object> recordInsurerOutcome(String quoteId, int version, String insurerId, String outcome,
            String errorName) {
        InsurerStatus status = InsurerStatus.valueOf(outcome);
        if (status != InsurerStatus.TIMED_OUT && status != InsurerStatus.UNAVAILABLE) {
            throw new IllegalArgumentException("record-insurer-outcome only records TIMED_OUT or UNAVAILABLE");
        }
        WorkRef workRef = WorkRef.of(quoteId, version, WorkRef.Kind.INSURER, insurerId);
        Instant now = Instant.now(clock);
        if (expireWaiter(workRef, quoteId, version, WorkRef.Kind.INSURER, insurerId, now).isEmpty()) {
            String reason = status == InsurerStatus.TIMED_OUT ? TIMEOUT_REASON : TASK_FAILED_REASON;
            table.updateIf(table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.resultSk(version, insurerId))
                    .updateExpression("SET #status = :status, reasonCode = :reason, revision = revision + :one, "
                            + "updatedAt = :now")
                    .conditionExpression("attribute_exists(" + QuoteKeys.PK + ") AND #status IN (:pending, :processing)")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":status", s(status.name()), ":reason", s(reason), ":one", n(1),
                            ":now", s(now.toString()), ":pending", s(InsurerStatus.PENDING.name()),
                            ":processing", s(InsurerStatus.PROCESSING.name())))
                    .build());
            table.refreshSummary(quoteId, version, now);
        }
        String current = table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.resultSk(version, insurerId))
                .map(item -> Attr.string(item, "status"))
                .orElse(status.name());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("insurerId", insurerId);
        output.put("status", current);
        output.put("error", errorName);
        return output;
    }

    /**
     * {@code record-enrichment-outcome}: usa o {@code resumePayload} quando o resultado já chegou; senão
     * devolve {@code FAILED} e marca o waiter {@code EXPIRED}.
     */
    public Map<String, Object> recordEnrichmentOutcome(String quoteId, int version, String errorName) {
        WorkRef workRef = WorkRef.of(quoteId, version, WorkRef.Kind.ENRICHMENT, null);
        Optional<String> resumePayload =
                expireWaiter(workRef, quoteId, version, WorkRef.Kind.ENRICHMENT, null, Instant.now(clock));
        if (resumePayload.isPresent()) {
            return readMap(resumePayload.get());
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", EnrichmentOutcome.ResultType.FAILED.name());
        output.put("errorClass", errorName == null ? TIMEOUT_REASON : errorName);
        return output;
    }

    /**
     * {@code consolidate}: aplica a precedência do PRD §8 sobre o que a versão gravou e grava
     * {@code TERMINAL} com o outbox {@code QuoteCompleted}, condicionado à {@code revision} lida.
     */
    public Map<String, Object> consolidate(String quoteId, int version) {
        for (int attempt = 1; ; attempt++) {
            Map<String, AttributeValue> versionItem = versionItem(quoteId, version);
            if (Phase.TERMINAL.name().equals(Attr.string(versionItem, "phase"))) {
                return consolidationOutput(Attr.string(versionItem, "outcome"), Attr.string(versionItem, "outcomeReason"));
            }
            ConsolidationResult result = Consolidation.consolidate(storedEligibility(versionItem),
                    enrichmentOutcome(quoteId, version, versionItem), Attr.strings(versionItem, "selectedInsurers"),
                    insurerResults(quoteId, version));
            int offerCount = table.count(QuoteKeys.quotePk(quoteId), QuoteKeys.offerPrefix(version));
            Instant now = Instant.now(clock);
            Map<String, AttributeValue> values = new HashMap<>(Map.of(
                    ":terminal", s(Phase.TERMINAL.name()),
                    ":outcome", s(result.outcome().name()),
                    ":missing", s(table.toJson(result.missingFields())),
                    ":one", n(1),
                    ":now", s(now.toString()),
                    ":revision", n(Attr.number(versionItem, "revision", 0))));
            String expression = "SET phase = :terminal, outcome = :outcome, missingFields = :missing, "
                    + "revision = revision + :one, updatedAt = :now";
            if (result.outcomeReason() != null) {
                values.put(":reason", s(result.outcomeReason().name()));
                expression += ", outcomeReason = :reason";
            }
            expression += " REMOVE " + QuoteKeys.GSI2_PK + ", " + QuoteKeys.GSI2_SK;
            Map<String, Object> completed = QuoteEvents.quoteCompleted(now, quoteId, version, result.outcome(),
                    result.outcomeReason(), offerCount);
            try {
                table.transact(List.of(
                        table.update(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version), expression,
                                "revision = :revision", Map.of(), values),
                        table.put(table.outboxItem(QuoteEvents.completedEventId(quoteId, version),
                                QuoteEvents.QUOTE_COMPLETED, completed, quoteId, version, now), QuoteTable.NOT_EXISTS)));
            } catch (TransactionCanceledException concurrentChange) {
                if (attempt >= CONSOLIDATION_ATTEMPTS) {
                    throw concurrentChange;
                }
                continue;
            }
            table.refreshSummary(quoteId, version, now);
            return consolidationOutput(result.outcome().name(),
                    result.outcomeReason() == null ? null : result.outcomeReason().name());
        }
    }

    /**
     * Marca o waiter {@code EXPIRED} só se ele ainda espera, criando-o se a {@code dispatch} nunca o gravou.
     * Devolve o {@code resumePayload} quando um resultado chegou antes.
     */
    private Optional<String> expireWaiter(WorkRef workRef, String quoteId, int version, WorkRef.Kind kind,
            String insurerId, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":expired", s("EXPIRED"), ":waiting", s("WAITING"), ":now", s(now.toString()),
                ":type", s(QuoteKeys.TYPE_WAITER), ":quoteId", s(quoteId), ":version", n(version),
                ":kind", s(kind.name())));
        String expression = "SET #status = :expired, updatedAt = :now, #type = :type, quoteId = :quoteId, "
                + "version = :version, kind = :kind";
        if (insurerId != null) {
            values.put(":insurerId", s(insurerId));
            expression += ", insurerId = :insurerId";
        }
        boolean expired = table.updateIf(table.update(QuoteKeys.waiterPk(workRef), QuoteKeys.META)
                .updateExpression(expression + " REMOVE " + QuoteKeys.GSI2_PK + ", " + QuoteKeys.GSI2_SK)
                .conditionExpression("attribute_not_exists(" + QuoteKeys.PK + ") OR #status = :waiting")
                .expressionAttributeNames(Map.of("#status", "status", "#type", QuoteKeys.TYPE))
                .expressionAttributeValues(values)
                .build());
        if (expired) {
            return Optional.empty();
        }
        return table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META)
                .map(waiter -> Attr.string(waiter, "resumePayload"));
    }

    private EnrichmentOutcome enrichmentOutcome(String quoteId, int version, Map<String, AttributeValue> versionItem) {
        if (!EligibilityDecision.ELIGIBLE.name().equals(Attr.string(versionItem, "eligibilityDecision"))) {
            return null;
        }
        WorkRef workRef = WorkRef.of(quoteId, version, WorkRef.Kind.ENRICHMENT, null);
        Optional<Map<String, AttributeValue>> evidence = enrichmentEvidence(quoteId, version);
        if (evidence.isPresent()) {
            EnrichmentOutcome.ResultType type =
                    EnrichmentOutcome.ResultType.valueOf(Attr.string(evidence.get(), "resultType"));
            List<String> notFoundRefs = new ArrayList<>();
            readTree(Attr.string(evidence.get(), "summary")).path("notFoundRefs")
                    .forEach(ref -> notFoundRefs.add(ref.asText()));
            return new EnrichmentOutcome(type, notFoundRefs);
        }
        // WHY: sem evidência, um waiter existente significa espera encerrada sem resultado (timeout ou falha)
        return table.get(QuoteKeys.waiterPk(workRef), QuoteKeys.META).isPresent() ? EnrichmentOutcome.failed() : null;
    }

    private List<InsurerResultView> insurerResults(String quoteId, int version) {
        return table.query(QuoteKeys.quotePk(quoteId), QuoteKeys.resultPrefix(version)).stream()
                .map(item -> new InsurerResultView(Attr.string(item, "insurerId"),
                        InsurerStatus.valueOf(Attr.string(item, "status")),
                        missingFields(Attr.string(item, "missingFields"))))
                .toList();
    }

    /** Evidência usada pela versão ({@code V#n#EVIDENCE#<workRef>}), gravada pelo {@code resume}. */
    private Optional<Map<String, AttributeValue>> enrichmentEvidence(String quoteId, int version) {
        WorkRef workRef = WorkRef.of(quoteId, version, WorkRef.Kind.ENRICHMENT, null);
        return table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.evidenceSk(version, workRef));
    }

    private EligibilityResult storedEligibility(Map<String, AttributeValue> versionItem) {
        String decision = Attr.string(versionItem, "eligibilityDecision");
        if (decision == null) {
            return null;
        }
        String reason = Attr.string(versionItem, "eligibilityReasonCode");
        return new EligibilityResult(EligibilityDecision.valueOf(decision),
                reason == null ? null : OutcomeReason.valueOf(reason),
                missingFields(Attr.string(versionItem, "eligibilityMissingFields")),
                Attr.string(versionItem, "eligibilityRuleVersion"));
    }

    private Map<String, AttributeValue> versionItem(String quoteId, int version) {
        return table.get(QuoteKeys.quotePk(quoteId), QuoteKeys.versionSk(version))
                .orElseThrow(() -> new IllegalStateException("quote " + quoteId + " has no version " + version));
    }

    QuoteSnapshot snapshot(Map<String, AttributeValue> versionItem) {
        try {
            return objectMapper.readValue(Attr.string(versionItem, "snapshot"), QuoteSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private List<MissingField> missingFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, MISSING_FIELDS);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return json == null ? objectMapper.missingNode() : objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> eligibilityOutput(EligibilityResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", result.decision().name());
        output.put("reasonCode", result.reasonCode() == null ? null : result.reasonCode().name());
        output.put("missingFields", result.missingFields());
        return output;
    }

    private static Map<String, Object> selectionOutput(List<String> insurers) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("insurers", insurers);
        output.put("count", insurers.size());
        return output;
    }

    private static Map<String, Object> consolidationOutput(String outcome, String outcomeReason) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("outcome", outcome);
        output.put("outcomeReason", outcomeReason);
        return output;
    }
}

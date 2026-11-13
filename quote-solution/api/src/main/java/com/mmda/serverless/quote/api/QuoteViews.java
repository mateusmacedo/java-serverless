package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.InsurerStatus;
import com.mmda.serverless.quote.domain.MissingField;
import com.mmda.serverless.quote.domain.MissingFieldLabels;
import com.mmda.serverless.quote.domain.Permission;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteActions;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.Revisions;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Monta as respostas de leitura a partir dos itens da versão. Revisões, ações permitidas e
 * disponibilidade das ofertas são derivadas aqui, com o {@code serverTime} da leitura (§4 e §8).
 */
final class QuoteViews {

    static final String SELECTION_STATUS = "INTENT_RECORDED";
    private static final TypeReference<List<MissingField>> MISSING_FIELDS = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    QuoteViews(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record QuoteAccepted(String quoteId, int version, String phase, String statusUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Me(String userId, String displayName, String organizationId, List<String> permissions) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record LabeledMissingField(String path, String label, String reasonCode) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record InsurerResult(String insurerId, String insurerName, String status, String reasonCode,
            List<LabeledMissingField> missingFields, String updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Selection(String selectionId, String offerId, String selectedAt, String status) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record QuoteVersion(String quoteId, int version, int currentVersion, JsonNode snapshot, String phase,
            String outcome, String outcomeReason, String supersededAt, long revision, String updatedAt,
            String serverTime, Integer retryAfterSeconds, List<LabeledMissingField> missingFields,
            List<InsurerResult> insurerResults, Selection selection, List<String> allowedActions) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Offer(String id, String insurerId, String insurerName, String premiumTotal, String currency,
            JsonNode installments, JsonNode coverages, JsonNode deductibles, String validUntil, boolean selectable,
            String unavailabilityReason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record OffersPage(long revision, String serverTime, List<Offer> items, String nextCursor) {
    }

    /** Resumo da cotação na listagem, lido do {@code META}: fase, resultado e ofertas da versão atual. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record QuoteSummary(String id, int currentVersion, String phase, String outcome, String createdAt,
            int offerCount) {

        static QuoteSummary of(Map<String, AttributeValue> meta) {
            return new QuoteSummary(Items.string(meta, "quoteId"), (int) Items.number(meta, "currentVersion", 1),
                    Items.string(meta, "phase"), Items.string(meta, "outcome"), Items.string(meta, "createdAt"),
                    (int) Items.number(meta, "offerCount", 0));
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record QuotesPage(List<QuoteSummary> items, String nextCursor) {
    }

    /** Itens de uma versão separados por tipo. */
    record VersionItems(Map<String, AttributeValue> version, List<Map<String, AttributeValue>> results,
            List<Map<String, AttributeValue>> offers, Map<String, AttributeValue> selection) {

        static VersionItems of(int version, List<Map<String, AttributeValue>> items) {
            String versionSk = QuoteKeys.versionSk(version);
            Map<String, AttributeValue> versionItem = null;
            Map<String, AttributeValue> selection = null;
            List<Map<String, AttributeValue>> results = new ArrayList<>();
            List<Map<String, AttributeValue>> offers = new ArrayList<>();
            for (Map<String, AttributeValue> item : items) {
                String sk = Items.string(item, QuoteKeys.SK);
                if (versionSk.equals(sk)) {
                    versionItem = item;
                } else if (sk.startsWith(QuoteKeys.resultPrefix(version))) {
                    results.add(item);
                } else if (sk.startsWith(QuoteKeys.offerPrefix(version))) {
                    offers.add(item);
                } else if (sk.equals(QuoteKeys.selectionSk(version))) {
                    selection = item;
                }
            }
            offers.sort(Comparator.comparing(offer -> Items.string(offer, QuoteKeys.SK)));
            results.sort(Comparator.comparing(result -> Items.string(result, QuoteKeys.SK)));
            return new VersionItems(versionItem, results, offers, selection);
        }
    }

    QuoteVersion version(String quoteId, int version, Map<String, AttributeValue> meta, VersionItems items,
            List<Permission> permissions, Instant serverTime, int retryAfterSeconds) {
        Map<String, AttributeValue> versionItem = items.version();
        QuoteActions.QuoteState state = state(version, meta);
        boolean anySelectable = items.offers().stream()
                .anyMatch(offer -> QuoteActions.unavailabilityReason(state, instant(offer, "validUntil"), serverTime) == null);
        long resultRevisions = items.results().stream().mapToLong(result -> Items.number(result, "revision", 0)).sum();
        long revision = Revisions.versionRevision(Items.number(versionItem, "revision", 0), resultRevisions,
                items.offers().size(), items.selection() != null);
        String phase = Items.string(versionItem, "phase");
        boolean terminal = Phase.TERMINAL.name().equals(phase);
        return new QuoteVersion(quoteId, version, state.currentVersion(), json(Items.string(versionItem, "snapshot")),
                phase, Items.string(versionItem, "outcome"), Items.string(versionItem, "outcomeReason"),
                Items.string(versionItem, "supersededAt"), revision, updatedAt(items), serverTime.toString(),
                terminal ? null : retryAfterSeconds, labeled(Items.string(versionItem, "missingFields")),
                items.results().stream().map(this::insurerResult).toList(), selection(items.selection()),
                QuoteActions.allowedActions(state, permissions, anySelectable));
    }

    OffersPage offers(int version, Map<String, AttributeValue> meta, VersionItems items, Instant serverTime,
            String cursor, int limit) {
        QuoteActions.QuoteState state = state(version, meta);
        int terminalResults = (int) items.results().stream()
                .filter(result -> InsurerStatus.valueOf(Items.string(result, "status")).isTerminal())
                .count();
        long revision = Revisions.offersRevision(items.offers().size(), terminalResults, items.selection() != null,
                Items.string(items.version(), "supersededAt") != null);
        String after = decodeCursor(cursor);
        List<Map<String, AttributeValue>> remaining = items.offers().stream()
                .filter(offer -> after == null || Items.string(offer, "offerId").compareTo(after) > 0)
                .toList();
        List<Offer> page = remaining.stream().limit(limit).map(offer -> offer(offer, state, serverTime)).toList();
        String nextCursor = remaining.size() > limit ? encodeCursor(page.getLast().id()) : null;
        return new OffersPage(revision, serverTime.toString(), page, nextCursor);
    }

    private Offer offer(Map<String, AttributeValue> item, QuoteActions.QuoteState state, Instant serverTime) {
        QuoteActions.UnavailabilityReason reason =
                QuoteActions.unavailabilityReason(state, instant(item, "validUntil"), serverTime);
        return new Offer(Items.string(item, "offerId"), Items.string(item, "insurerId"),
                Items.string(item, "insurerName"), Items.string(item, "premiumTotal"), Items.string(item, "currency"),
                json(Items.string(item, "installments")), json(Items.string(item, "coverages")),
                json(Items.string(item, "deductibles")), Items.string(item, "validUntil"), reason == null,
                reason == null ? null : reason.name());
    }

    private InsurerResult insurerResult(Map<String, AttributeValue> item) {
        return new InsurerResult(Items.string(item, "insurerId"), Items.string(item, "insurerName"),
                Items.string(item, "status"), Items.string(item, "reasonCode"),
                labeled(Items.string(item, "missingFields")), Items.string(item, "updatedAt"));
    }

    private static Selection selection(Map<String, AttributeValue> item) {
        if (item == null) {
            return null;
        }
        return new Selection(Items.string(item, "selectionId"), Items.string(item, "offerId"),
                Items.string(item, "selectedAt"), SELECTION_STATUS);
    }

    private static QuoteActions.QuoteState state(int version, Map<String, AttributeValue> meta) {
        return new QuoteActions.QuoteState(version, (int) Items.number(meta, "currentVersion", version),
                Items.string(meta, "selectionId") != null);
    }

    /** Mudança mais recente entre a versão, os resultados, as ofertas e a seleção. */
    private static String updatedAt(VersionItems items) {
        List<String> instants = new ArrayList<>();
        instants.add(Items.string(items.version(), "updatedAt"));
        items.results().forEach(result -> instants.add(Items.string(result, "updatedAt")));
        items.offers().forEach(offer -> instants.add(Items.string(offer, "createdAt")));
        if (items.selection() != null) {
            instants.add(Items.string(items.selection(), "selectedAt"));
        }
        return instants.stream().filter(value -> value != null).map(Instant::parse).max(Comparator.naturalOrder())
                .map(Instant::toString).orElse(null);
    }

    private List<LabeledMissingField> labeled(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, MISSING_FIELDS).stream()
                    .map(field -> new LabeledMissingField(field.path(), MissingFieldLabels.labelOf(field.path()),
                            field.reasonCode()))
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Instant instant(Map<String, AttributeValue> item, String name) {
        String value = Items.string(item, name);
        return value == null ? null : Instant.parse(value);
    }

    static String encodeCursor(String offerId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(offerId.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("cursor", "Cursor inválido.")));
        }
    }
}

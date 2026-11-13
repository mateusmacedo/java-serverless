package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.CanonicalJson;
import com.mmda.serverless.quote.domain.Deadlines;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.Idempotency;
import com.mmda.serverless.quote.domain.Outcome;
import com.mmda.serverless.quote.domain.Permission;
import com.mmda.serverless.quote.domain.Phase;
import com.mmda.serverless.quote.domain.QuoteKeys;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Cotações da API de produto (contrato compartilhado). A criação só aceita
 * duravelmente: a execução é
 * iniciada depois, pelo outbox, e a resposta 202 aponta o recurso de
 * acompanhamento.
 */
@Path("/v1/quotes")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class QuoteResource {

    private static final Logger LOG = Logger.getLogger(QuoteResource.class);
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final QuoteStore store;
    private final Catalog catalog;
    private final Caller caller;
    private final ObjectMapper objectMapper;
    private final QuoteApiConfig config;
    private final Clock clock;
    private final QuoteViews views;
    private final RequestCorrelation correlation;

    QuoteResource(QuoteStore store, Catalog catalog, Caller caller, ObjectMapper objectMapper, QuoteApiConfig config,
            Clock clock, RequestCorrelation correlation) {
        this.store = store;
        this.catalog = catalog;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.config = config;
        this.clock = clock;
        this.views = new QuoteViews(objectMapper);
        this.correlation = correlation;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey, String body) {
        String organizationId = caller.require(Permission.QUOTES_WRITE);
        if (!Idempotency.isValidKey(idempotencyKey)) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError(IDEMPOTENCY_KEY,
                    "Header obrigatório: até 200 caracteres ASCII visíveis, sem espaços.")));
        }
        JsonNode json;
        try {
            json = CanonicalJson.parse(body == null ? "" : body);
        } catch (CanonicalJson.InvalidJsonException invalid) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("$", "JSON inválido.")));
        }
        QuoteSnapshot snapshot = CreateQuoteRequest.parse(json)
                .resolve(catalog.organization(organizationId).orElse(null), catalog);

        Instant acceptedAt = Instant.now(clock);
        String snapshotJson = toJson(snapshot);
        QuoteStore.CreatedQuote created = store.create(new QuoteStore.NewQuote(
                new Idempotency.Key(organizationId, Idempotency.Operation.CREATE_QUOTE, Idempotency.NO_SCOPE,
                        idempotencyKey),
                Hashes.sha256Hex(CanonicalJson.canonicalize(json)),
                UUID.randomUUID().toString(),
                organizationId,
                caller.userId(),
                snapshotJson,
                Hashes.sha256Hex(CanonicalJson.canonicalize(snapshotJson)),
                acceptedAt,
                Deadlines.versionDeadline(acceptedAt, Duration.ofSeconds(config.deadlineSeconds()))));
        LOG.infof("quote accepted quoteId=%s version=%d organizationId=%s correlationId=%s", created.quoteId(),
                created.version(), organizationId, correlation.id());
        String statusUrl = QuoteKeys.statusUrl(created.quoteId(), created.version());
        return Response.accepted(new QuoteViews.QuoteAccepted(created.quoteId(), created.version(), created.phase(),
                statusUrl))
                // WHY: location(URI) resolve o caminho contra a URI base da aplicação, que
                // atrás do API Gateway não
                // tem o stage; o contrato diz que o Location é o mesmo valor do statusUrl
                .header(HttpHeaders.LOCATION, statusUrl)
                .build();
    }

    /**
     * Nova versão (T17): substitui a atual com o novo pedido, resolvido no catálogo
     * como na criação. A versão
     * substituída continua consultável, com os resultados dela isolados, e deixa de
     * aceitar seleção.
     */
    @POST
    @Path("/{quoteId}/versions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createVersion(@PathParam("quoteId") String quoteId,
            @HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey, String body) {
        String organizationId = caller.require(Permission.QUOTES_WRITE);
        ownedMeta(quoteId, organizationId);
        if (!Idempotency.isValidKey(idempotencyKey)) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError(IDEMPOTENCY_KEY,
                    "Header obrigatório: até 200 caracteres ASCII visíveis, sem espaços.")));
        }
        JsonNode json;
        try {
            json = CanonicalJson.parse(body == null ? "" : body);
        } catch (CanonicalJson.InvalidJsonException invalid) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("$", "JSON inválido.")));
        }
        CreateVersionRequest request = CreateVersionRequest.parse(json);
        QuoteSnapshot snapshot = request.quote().resolve(catalog.organization(organizationId).orElse(null), catalog);
        Instant acceptedAt = Instant.now(clock);
        String snapshotJson = toJson(snapshot);
        QuoteStore.CreatedQuote created = store.createVersion(new QuoteStore.NewVersion(
                new Idempotency.Key(organizationId, Idempotency.Operation.CREATE_VERSION, quoteId, idempotencyKey),
                Hashes.sha256Hex(CanonicalJson.canonicalize(json)),
                quoteId,
                request.expectedVersion(),
                organizationId,
                snapshotJson,
                Hashes.sha256Hex(CanonicalJson.canonicalize(snapshotJson)),
                acceptedAt,
                Deadlines.versionDeadline(acceptedAt, Duration.ofSeconds(config.deadlineSeconds()))));
        LOG.infof("version accepted quoteId=%s version=%d organizationId=%s correlationId=%s", created.quoteId(),
                created.version(), organizationId, correlation.id());
        String statusUrl = QuoteKeys.statusUrl(created.quoteId(), created.version());
        return Response.accepted(new QuoteViews.QuoteAccepted(created.quoteId(), created.version(), created.phase(),
                statusUrl))
                .header(HttpHeaders.LOCATION, statusUrl)
                .build();
    }

    /**
     * Cotações da organização, as mais novas primeiro, com o resumo da versão atual
     * gravado no {@code META}. Os
     * filtros são combinados; ao mudar a combinação, o cliente recomeça sem cursor.
     */
    @GET
    public QuoteViews.QuotesPage list(@QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit, @QueryParam("phase") String phase,
            @QueryParam("outcome") String outcome, @QueryParam("createdFrom") String createdFrom,
            @QueryParam("createdTo") String createdTo) {
        String organizationId = caller.require(Permission.QUOTES_READ);
        int pageSize = Paging.limit(limit);
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        QuoteStore.QuoteFilter filter = new QuoteStore.QuoteFilter(
                enumValue(Phase.class, "phase", phase, errors), enumValue(Outcome.class, "outcome", outcome, errors),
                instant("createdFrom", createdFrom, errors), instant("createdTo", createdTo, errors));
        if (filter.createdFrom() != null && filter.createdTo() != null
                && filter.createdFrom().isAfter(filter.createdTo())) {
            errors.add(new ErrorResponse.FieldError("createdTo", "Use um instante igual ou posterior a createdFrom."));
        }
        String after = position(cursor);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        IndexPages.Page page = store.listQuotes(organizationId, filter, after, pageSize);
        return new QuoteViews.QuotesPage(page.items().stream().map(QuoteViews.QuoteSummary::of).toList(),
                page.nextPosition() == null ? null : QuoteViews.encodeCursor(page.nextPosition()));
    }

    /**
     * Seleção definitiva de uma oferta (RN13): registra a intenção de proposta, sem
     * emitir proposta. A mesma oferta
     * devolve a seleção existente, mesmo com outra chave (T28); a oferta expirada,
     * de versão substituída ou fora da
     * versão é recusada sem gravar nada (T18). Permitida durante {@code QUOTING},
     * enquanto as outras consultas seguem.
     */
    @POST
    @Path("/{quoteId}/versions/{version}/selection")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response select(@PathParam("quoteId") String quoteId, @PathParam("version") String version,
            @HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey, String body) {
        String organizationId = caller.require(Permission.OFFERS_SELECT);
        Map<String, AttributeValue> meta = ownedMeta(quoteId, organizationId);
        MutationRequests.requireIdempotencyKey(idempotencyKey);
        JsonNode json = MutationRequests.json(body);
        String offerId = MutationRequests.offerId(json);
        int number = versionNumber(version);
        QuoteStore.RecordedSelection selection = store.select(new QuoteStore.NewSelection(
                new Idempotency.Key(organizationId, Idempotency.Operation.SELECT_OFFER, quoteId, idempotencyKey),
                Hashes.sha256Hex(CanonicalJson.canonicalize(json)), quoteId, number, offerId, organizationId,
                caller.userId(), Instant.now(clock)), meta);
        LOG.infof("offer selected quoteId=%s version=%d selectionId=%s organizationId=%s correlationId=%s", quoteId,
                number, selection.selectionId(), organizationId, correlation.id());
        return Response.status(Response.Status.CREATED)
                .entity(new QuoteViews.Selection(selection.selectionId(), selection.offerId(), selection.selectedAt(),
                        QuoteViews.SELECTION_STATUS))
                .build();
    }

    @GET
    @Path("/{quoteId}/versions/{version}")
    public QuoteViews.QuoteVersion version(@PathParam("quoteId") String quoteId, @PathParam("version") String version) {
        String organizationId = caller.require(Permission.QUOTES_READ);
        int number = versionNumber(version);
        Map<String, AttributeValue> meta = ownedMeta(quoteId, organizationId);
        QuoteViews.VersionItems items = QuoteViews.VersionItems.of(number, store.versionItems(quoteId, number));
        if (items.version() == null) {
            throw ApiException.notFound();
        }
        return views.version(quoteId, number, meta, items, caller.permissions(), Instant.now(clock),
                config.retryAfterSeconds());
    }

    @GET
    @Path("/{quoteId}/versions/{version}/offers")
    public QuoteViews.OffersPage offers(@PathParam("quoteId") String quoteId, @PathParam("version") String version,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit) {
        String organizationId = caller.require(Permission.QUOTES_READ);
        int pageSize = Paging.limit(limit);
        int number = versionNumber(version);
        Map<String, AttributeValue> meta = ownedMeta(quoteId, organizationId);
        QuoteViews.VersionItems items = QuoteViews.VersionItems.of(number, store.versionItems(quoteId, number));
        if (items.version() == null) {
            throw ApiException.notFound();
        }
        return views.offers(number, meta, items, Instant.now(clock), cursor, pageSize);
    }

    /**
     * Cotação de outra organização responde 404, como a inexistente: não revela que
     * ela existe.
     */
    private Map<String, AttributeValue> ownedMeta(String quoteId, String organizationId) {
        return store.meta(quoteId)
                .filter(meta -> organizationId.equals(Items.string(meta, "organizationId")))
                .orElseThrow(ApiException::notFound);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String value,
            List<ErrorResponse.FieldError> errors) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            errors.add(new ErrorResponse.FieldError(name, "Valor fora do enum do contrato."));
            return null;
        }
    }

    private static Instant instant(String name, String value, List<ErrorResponse.FieldError> errors) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException malformed) {
            errors.add(new ErrorResponse.FieldError(name, "Use data e hora ISO 8601, como 2026-11-12T00:00:00Z."));
            return null;
        }
    }

    /**
     * Posição de um cursor da listagem; fora do formato
     * {@code <createdAt>#<quoteId>} ou maior que uma chave do
     * índice, responde 400.
     */
    static String position(String cursor) {
        String position = QuoteViews.decodeCursor(cursor);
        if (position != null && (IndexPages.idOf(position) == null
                || position.getBytes(StandardCharsets.UTF_8).length > IndexPages.MAX_POSITION_BYTES)) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("cursor", "Cursor inválido.")));
        }
        return position;
    }

    private static int versionNumber(String version) {
        try {
            int number = Integer.parseInt(version);
            if (number >= 1 && number <= 9999) {
                return number;
            }
        } catch (NumberFormatException invalid) {
            // WHY: versão malformada é tratada como recurso inexistente, sem revelar o
            // formato interno
        }
        throw ApiException.notFound();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

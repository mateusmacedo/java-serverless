package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.CanonicalJson;
import com.mmda.serverless.quote.domain.Deadlines;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.Idempotency;
import com.mmda.serverless.quote.domain.Permission;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Cotações da API de produto (contrato compartilhado). A criação só aceita duravelmente: a execução é
 * iniciada depois, pelo outbox, e a resposta 202 aponta o recurso de acompanhamento.
 */
@Path("/v1/quotes")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class QuoteResource {

    private static final Logger LOG = Logger.getLogger(QuoteResource.class);
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_OFFERS_PAGE = 100;

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
                // WHY: location(URI) resolve o caminho contra a URI base da aplicação, que atrás do API Gateway não
                // tem o stage; o contrato diz que o Location é o mesmo valor do statusUrl
                .header(HttpHeaders.LOCATION, statusUrl)
                .build();
    }

    /**
     * Nova versão (T17): substitui a atual com o novo pedido, resolvido no catálogo como na criação. A versão
     * substituída continua consultável, com os resultados dela isolados, e deixa de aceitar seleção.
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
            @QueryParam("cursor") String cursor, @QueryParam("limit") @DefaultValue("20") String limit) {
        String organizationId = caller.require(Permission.QUOTES_READ);
        int pageSize = pageSize(limit);
        int number = versionNumber(version);
        Map<String, AttributeValue> meta = ownedMeta(quoteId, organizationId);
        QuoteViews.VersionItems items = QuoteViews.VersionItems.of(number, store.versionItems(quoteId, number));
        if (items.version() == null) {
            throw ApiException.notFound();
        }
        return views.offers(number, meta, items, Instant.now(clock), cursor, pageSize);
    }

    /**
     * WHY: com {@code int} no {@code @QueryParam}, um valor não numérico vira {@code NotFoundException} no
     * Quarkus REST, e o contrato pede 400 para parâmetro inválido.
     */
    private static int pageSize(String limit) {
        try {
            int size = Integer.parseInt(limit);
            if (size >= 1 && size <= MAX_OFFERS_PAGE) {
                return size;
            }
        } catch (NumberFormatException invalid) {
            // NOTE: cai no mesmo erro de validação do valor fora da faixa
        }
        throw ApiException.validation(List.of(new ErrorResponse.FieldError("limit", "Use um valor de 1 a 100.")));
    }

    /** Cotação de outra organização responde 404, como a inexistente: não revela que ela existe. */
    private Map<String, AttributeValue> ownedMeta(String quoteId, String organizationId) {
        return store.meta(quoteId)
                .filter(meta -> organizationId.equals(Items.string(meta, "organizationId")))
                .orElseThrow(ApiException::notFound);
    }

    private static int versionNumber(String version) {
        try {
            int number = Integer.parseInt(version);
            if (number >= 1 && number <= 9999) {
                return number;
            }
        } catch (NumberFormatException invalid) {
            // WHY: versão malformada é tratada como recurso inexistente, sem revelar o formato interno
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

package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mmda.serverless.quote.domain.Permission;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Catálogo do laboratório (contrato compartilhado, "Identidade, dados de
 * entrada e permissões"): as fixtures
 * autorizadas de {@code contracts/fixtures/catalog.json}, as mesmas do
 * frontend. Clientes, veículos e condutores são
 * só os da organização de quem chama; os perfis de cobertura valem para todas.
 * Exige {@code quotes:write}, porque o
 * catálogo existe para preencher a cotação.
 */
@Path("/v1/catalog")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {

    private static final int MAX_SEARCH_LENGTH = 100;

    private final Catalog catalog;
    private final Caller caller;

    CatalogResource(Catalog catalog, Caller caller) {
        this.catalog = catalog;
        this.caller = caller;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record CatalogItem(String id, String label) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record CatalogPage(List<CatalogItem> items, String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record CoverageProfilesPage(List<QuoteSnapshot.CoverageProfile> items, String nextCursor) {
    }

    @GET
    @Path("/customers")
    public CatalogPage customers(@QueryParam("search") String search, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit) {
        Catalog.Organization organization = organization();
        return page(organization == null ? List.of() : organization.customers(), QuoteSnapshot.Party::id,
                QuoteSnapshot.Party::label, search, cursor, limit);
    }

    @GET
    @Path("/vehicles")
    public CatalogPage vehicles(@QueryParam("search") String search, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit) {
        Catalog.Organization organization = organization();
        return page(organization == null ? List.of() : organization.vehicles(), QuoteSnapshot.Vehicle::id,
                QuoteSnapshot.Vehicle::label, search, cursor, limit);
    }

    @GET
    @Path("/drivers")
    public CatalogPage drivers(@QueryParam("search") String search, @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit) {
        Catalog.Organization organization = organization();
        return page(organization == null ? List.of() : organization.drivers(), QuoteSnapshot.Driver::id,
                QuoteSnapshot.Driver::label, search, cursor, limit);
    }

    @GET
    @Path("/coverage-profiles")
    public CoverageProfilesPage coverageProfiles(@QueryParam("search") String search,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit) {
        organization();
        Selected<QuoteSnapshot.CoverageProfile> selected = select(catalog.coverageProfiles(),
                QuoteSnapshot.CoverageProfile::code, QuoteSnapshot.CoverageProfile::label, search, cursor, limit);
        return new CoverageProfilesPage(selected.items(), selected.nextCursor());
    }

    /**
     * Organização do catálogo; nula quando a organização do token não tem fixtures,
     * e as listas ficam vazias.
     */
    private Catalog.Organization organization() {
        return catalog.organization(caller.require(Permission.QUOTES_WRITE)).orElse(null);
    }

    private <T> CatalogPage page(List<T> entries, Function<T, String> id, Function<T, String> label, String search,
            String cursor, String limit) {
        Selected<T> selected = select(entries, id, label, search, cursor, limit);
        return new CatalogPage(selected.items().stream()
                .map(entry -> new CatalogItem(id.apply(entry), label.apply(entry))).toList(), selected.nextCursor());
    }

    private record Selected<T>(List<T> items, String nextCursor) {
    }

    /**
     * Busca sem diferença de maiúsculas e acentos no rótulo e no id, na ordem do
     * catálogo. O cursor é o id do
     * último item devolvido; um id que não está no resultado da busca atual
     * responde 400, para o cliente recomeçar.
     */
    private static <T> Selected<T> select(List<T> entries, Function<T, String> id, Function<T, String> label,
            String search, String cursor, String limit) {
        int size = Paging.limit(limit);
        if (search != null && MutationRequests.characters(search) > MAX_SEARCH_LENGTH) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("search",
                    "Use até " + MAX_SEARCH_LENGTH + " caracteres.")));
        }
        String term = search == null ? "" : fold(search.strip());
        List<T> found = entries.stream()
                .filter(entry -> term.isEmpty() || fold(label.apply(entry)).contains(term)
                        || fold(id.apply(entry)).contains(term))
                .toList();
        int start = 0;
        String after = QuoteViews.decodeCursor(cursor);
        if (after != null) {
            int index = found.stream().map(id).toList().indexOf(after);
            if (index < 0) {
                throw ApiException.validation(List.of(new ErrorResponse.FieldError("cursor", "Cursor inválido.")));
            }
            start = index + 1;
        }
        List<T> page = found.subList(start, Math.min(found.size(), start + size));
        String next = start + size < found.size() ? QuoteViews.encodeCursor(id.apply(page.getLast())) : null;
        return new Selected<>(page, next);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}

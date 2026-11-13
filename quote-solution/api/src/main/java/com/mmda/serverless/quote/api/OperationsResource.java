package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mmda.serverless.quote.domain.CanonicalJson;
import com.mmda.serverless.quote.domain.Hashes;
import com.mmda.serverless.quote.domain.Idempotency;
import com.mmda.serverless.quote.domain.Operations;
import com.mmda.serverless.quote.domain.Permission;
import com.mmda.serverless.quote.domain.QuoteIssues;
import com.mmda.serverless.quote.domain.QuoteKeys;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Operações restritas (§8 e §11 do desenho, M5): ocorrências da quote e da provider, reconciliação manual de uma
 * consulta conhecida da organização e a situação dela. Nada aqui leva token, payload bruto nem o motivo informado.
 */
@Path("/v1/operations")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class OperationsResource {

    private static final Logger LOG = Logger.getLogger(OperationsResource.class);

    private final OperationsStore store;
    private final Caller caller;
    private final Clock clock;
    private final RequestCorrelation correlation;

    OperationsResource(OperationsStore store, Caller caller, Clock clock, RequestCorrelation correlation) {
        this.store = store;
        this.caller = caller;
        this.clock = clock;
        this.correlation = correlation;
    }

    /**
     * Ocorrências da organização. O callback sem correlação não tem organização e não aparece para nenhuma (§11 do
     * desenho); aparece no alarme e no log da provider.
     */
    @GET
    @Path("/issues")
    public OperationViews.IssuesPage issues(@QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue(Paging.DEFAULT_LIMIT) String limit, @QueryParam("type") String type,
            @QueryParam("status") String status) {
        String organizationId = caller.require(Permission.OPERATIONS_READ);
        int pageSize = Paging.limit(limit);
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        Operations.IssueType issueType = null;
        if (type != null && !type.isEmpty()) {
            issueType = Arrays.stream(Operations.IssueType.values()).filter(value -> value.name().equals(type))
                    .findFirst().orElse(null);
            if (issueType == null) {
                errors.add(new ErrorResponse.FieldError("type", "Valor fora do enum do contrato."));
            }
        }
        boolean knownStatus = status == null || status.isEmpty()
                || Arrays.stream(QuoteIssues.Status.values()).anyMatch(value -> value.name().equals(status));
        if (!knownStatus) {
            errors.add(new ErrorResponse.FieldError("status", "Valor fora do enum do contrato."));
        }
        String after = QuoteResource.position(cursor);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        IndexPages.Page page = store.issues(organizationId,
                new OperationsStore.IssueFilter(issueType, status == null || status.isEmpty() ? null : status), after,
                pageSize);
        List<Permission> permissions = caller.permissions();
        return new OperationViews.IssuesPage(
                page.items().stream().map(item -> OperationViews.Issue.of(item, permissions)).toList(),
                page.nextPosition() == null ? null : QuoteViews.encodeCursor(page.nextPosition()));
    }

    /**
     * Reconciliação manual: aceita só para consulta conhecida da organização ({@code QUERYREF#}); a de outra
     * organização ou desconhecida responde 404, sem revelar que existe. Grava a auditoria (motivo e quem pediu) e o
     * pedido para a provider numa transação; o 202 não significa consulta recuperada.
     */
    @POST
    @Path("/queries/{queryId}/reconcile")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reconcile(@PathParam("queryId") String queryId,
            @HeaderParam(QuoteResource.IDEMPOTENCY_KEY) String idempotencyKey, String body) {
        String organizationId = caller.require(Permission.OPERATIONS_RECONCILE);
        if (!store.knownQuery(queryId, organizationId)) {
            throw ApiException.notFound();
        }
        MutationRequests.requireIdempotencyKey(idempotencyKey);
        JsonNode json = MutationRequests.json(body);
        String reason = MutationRequests.reason(json);
        OperationsStore.AcceptedOperation accepted = store.request(new OperationsStore.NewReconcile(
                new Idempotency.Key(organizationId, Idempotency.Operation.RECONCILE, queryId, idempotencyKey),
                Hashes.sha256Hex(CanonicalJson.canonicalize(json)), Operations.newOperationId(), organizationId,
                queryId, reason, caller.userId(), Instant.now(clock)));
        LOG.infof("reconcile requested operationId=%s queryId=%s organizationId=%s requestedBy=%s correlationId=%s",
                accepted.operationId(), queryId, organizationId, caller.userId(), correlation.id());
        String statusUrl = QuoteKeys.operationStatusUrl(accepted.operationId());
        // NOTE: o contrato fixa QUEUED no 202, também no reenvio; a situação real é a do statusUrl
        return Response.accepted(new OperationViews.ReconcileAccepted(accepted.operationId(),
                        Operations.OperationStatus.QUEUED.name(), statusUrl))
                .header(HttpHeaders.LOCATION, statusUrl)
                .build();
    }

    @GET
    @Path("/{operationId}")
    public OperationViews.Operation operation(@PathParam("operationId") String operationId) {
        String organizationId = caller.require(Permission.OPERATIONS_READ);
        return store.operation(operationId, organizationId).map(OperationViews.Operation::of)
                .orElseThrow(ApiException::notFound);
    }
}

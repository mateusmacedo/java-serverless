package com.mmda.serverless.quote.api;

import com.mmda.serverless.quote.domain.Permission;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Usuário, organização e permissões, derivados do access token (§11 do
 * desenho).
 */
@Path("/v1/me")
@Authenticated
public class MeResource {

    private final Caller caller;

    MeResource(Caller caller) {
        this.caller = caller;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public QuoteViews.Me me() {
        return new QuoteViews.Me(caller.userId(), caller.displayName(), caller.organizationId(),
                caller.permissions().stream().map(Permission::code).toList());
    }
}

package com.mmda.serverless.quote.api;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * {@code correlationId} da requisição: o header {@code X-Correlation-Id} do
 * cliente, quando bem formado,
 * ou um UUID novo. Vai nos erros, no header da resposta e nos logs.
 */
@RequestScoped
public class RequestCorrelation {

    static final String HEADER = "X-Correlation-Id";
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private String id;

    public String id() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    @ServerRequestFilter(preMatching = true)
    public void capture(ContainerRequestContext request) {
        String received = request.getHeaderString(HEADER);
        id = received != null && ACCEPTED.matcher(received).matches() ? received : UUID.randomUUID().toString();
    }

    @ServerResponseFilter
    public void expose(ContainerResponseContext response) {
        response.getHeaders().putSingle(HEADER, id());
    }
}

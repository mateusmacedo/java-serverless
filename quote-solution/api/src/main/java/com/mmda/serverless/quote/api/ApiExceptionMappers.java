package com.mmda.serverless.quote.api;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Converte toda falha no erro padrão do contrato, sem stack trace nem detalhe
 * interno.
 */
public class ApiExceptionMappers {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMappers.class);

    private final RequestCorrelation correlation;

    ApiExceptionMappers(RequestCorrelation correlation) {
        this.correlation = correlation;
    }

    @ServerExceptionMapper
    public Response apiException(ApiException exception) {
        return error(exception.status(), exception.code(), exception.getMessage(), exception.fieldErrors());
    }

    @ServerExceptionMapper({ AuthenticationFailedException.class, UnauthorizedException.class })
    public Response unauthenticated(RuntimeException exception) {
        return Response.fromResponse(error(401, "UNAUTHENTICATED", "Sessão inválida ou ausente.", List.of()))
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .build();
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException exception) {
        ApiException forbidden = ApiException.forbidden();
        return error(forbidden.status(), forbidden.code(), forbidden.getMessage(), List.of());
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException exception) {
        ApiException notFound = ApiException.notFound();
        return error(notFound.status(), notFound.code(), notFound.getMessage(), List.of());
    }

    @ServerExceptionMapper
    public Response unsupportedMediaType(NotSupportedException exception) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type application/json.", List.of());
    }

    @ServerExceptionMapper
    public Response methodNotAllowed(NotAllowedException exception) {
        return error(405, "METHOD_NOT_ALLOWED", "Método não permitido neste recurso.", List.of());
    }

    @ServerExceptionMapper
    public Response webApplication(WebApplicationException exception) {
        int status = exception.getResponse().getStatus();
        if (status >= 500) {
            LOG.errorf(exception, "request failed, correlationId=%s", correlation.id());
            return error(500, "INTERNAL_ERROR", "Erro interno.", List.of());
        }
        return error(status, "REQUEST_REJECTED", "Requisição recusada.", List.of());
    }

    @ServerExceptionMapper
    public Response unexpected(Exception exception) {
        LOG.errorf(exception, "unexpected failure, correlationId=%s", correlation.id());
        return error(500, "INTERNAL_ERROR", "Erro interno.", List.of());
    }

    private Response error(int status, String code, String message, List<ErrorResponse.FieldError> fieldErrors) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorResponse(code, message, correlation.id(), fieldErrors))
                .build();
    }
}

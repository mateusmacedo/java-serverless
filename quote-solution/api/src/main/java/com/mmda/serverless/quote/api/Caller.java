package com.mmda.serverless.quote.api;

import com.mmda.serverless.quote.domain.Permission;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Usuário autenticado: organização e permissões vêm dos grupos do Cognito
 * ({@code cognito:groups}), nunca
 * do body. Sem grupo de organização, todo endpoint responde 403.
 */
@RequestScoped
public class Caller {

    private final SecurityIdentity identity;
    private final JsonWebToken token;

    Caller(SecurityIdentity identity, JsonWebToken token) {
        this.identity = identity;
        this.token = token;
    }

    public String organizationId() {
        return Permission.organizationFromGroups(identity.getRoles()).orElseThrow(ApiException::forbidden);
    }

    public List<Permission> permissions() {
        return Permission.fromGroups(identity.getRoles());
    }

    /**
     * Organização do usuário, depois de conferir a permissão exigida pelo endpoint.
     */
    public String require(Permission permission) {
        String organizationId = organizationId();
        if (!permissions().contains(permission)) {
            throw ApiException.forbidden();
        }
        return organizationId;
    }

    public String userId() {
        return token.getSubject();
    }

    public String displayName() {
        String username = CognitoAccessTokenAugmentor.claim(token, "username");
        return username != null ? username : userId();
    }
}

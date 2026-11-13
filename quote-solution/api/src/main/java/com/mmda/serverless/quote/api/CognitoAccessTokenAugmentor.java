package com.mmda.serverless.quote.api;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonString;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Aceita só access tokens do app client da stack (§11 do desenho). WHY: o
 * access token do Cognito não tem
 * {@code aud}, e um ID token do mesmo pool passaria na assinatura;
 * {@code token_use} e {@code client_id}
 * fecham as duas brechas.
 */
@ApplicationScoped
public class CognitoAccessTokenAugmentor implements SecurityIdentityAugmentor {

    private final QuoteApiConfig config;

    CognitoAccessTokenAugmentor(QuoteApiConfig config) {
        this.config = config;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(identity);
        }
        if (!"access".equals(claim(token, "token_use"))
                || !config.auth().clientId().equals(claim(token, "client_id"))) {
            return Uni.createFrom()
                    .failure(new AuthenticationFailedException("token is not an access token of the app client"));
        }
        return Uni.createFrom().item(identity);
    }

    static String claim(JsonWebToken token, String name) {
        Object value = token.getClaim(name);
        if (value instanceof JsonString json) {
            return json.getString();
        }
        return value == null ? null : value.toString();
    }
}

package com.mmda.serverless.quote.api;

import io.smallrye.jwt.build.Jwt;
import java.util.List;

/**
 * Access tokens no formato do Cognito, assinados com a chave privada que o {@link JwtKeyResource} publica em
 * {@code smallrye.jwt.sign.key}. Só valem em testes que declaram esse recurso.
 */
final class TestTokens {

    static final String ISSUER = "https://issuer.test/pool";
    static final String CLIENT_ID = "test-client";

    private TestTokens() {
    }

    static String access(String subject, String... groups) {
        return token(subject, "access", CLIENT_ID, groups);
    }

    static String token(String subject, String tokenUse, String clientId, String... groups) {
        return Jwt.issuer(ISSUER)
                .subject(subject)
                .claim("username", subject)
                .claim("token_use", tokenUse)
                .claim("client_id", clientId)
                .claim("cognito:groups", List.of(groups))
                .jws().keyId("test")
                .sign();
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }
}

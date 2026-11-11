package com.mmda.serverless.quote.api;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

/**
 * Par RSA 2048 gerado em memória uma vez por JVM. A chave pública verifica os tokens da API e a privada assina
 * os de {@link TestTokens}. As duas seguem inline em Base64, como no gerador de chaves do smallrye-jwt em teste.
 */
public class JwtKeyResource implements QuarkusTestResourceLifecycleManager {

    private static final KeyPair KEYS = generate();

    @Override
    public Map<String, String> start() {
        Base64.Encoder base64 = Base64.getEncoder();
        return Map.of(
                "mp.jwt.verify.publickey", base64.encodeToString(KEYS.getPublic().getEncoded()),
                "smallrye.jwt.sign.key", base64.encodeToString(KEYS.getPrivate().getEncoded()));
    }

    @Override
    public void stop() {
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("could not generate the test RSA key pair", exception);
        }
    }
}

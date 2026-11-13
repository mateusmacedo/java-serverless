package com.mmda.serverless.quote.functions;

import com.mmda.serverless.quote.domain.WorkRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Cifra os task tokens guardados nos waiters (AES-256-GCM, §8 do desenho). A
 * chave é derivada do segredo
 * {@code token-key} por HMAC-SHA256 com um rótulo de uso, e o {@code workRef}
 * entra como dado associado:
 * um token cifrado só decifra no waiter que o guardou.
 */
@ApplicationScoped
public class TokenCipher {

    private static final String FORMAT = "v1";
    private static final byte[] KEY_LABEL = "quote-solution/task-token/v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Supplier<String> secret;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    @Inject
    TokenCipher(SecretsManagerClient secretsManager, QuoteFunctionsConfig config) {
        this(() -> secretsManager.getSecretValue(builder -> builder.secretId(config.tokenKeySecretId()))
                .secretString());
    }

    TokenCipher(Supplier<String> secret) {
        this.secret = secret;
    }

    public String encrypt(String token, WorkRef workRef) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(workRef.value().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder base64 = Base64.getEncoder();
            return FORMAT + ":" + base64.encodeToString(iv) + ":" + base64.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("could not encrypt the task token", exception);
        }
    }

    public String decrypt(String ciphertext, WorkRef workRef) {
        String[] parts = ciphertext.split(":");
        if (parts.length != 3 || !FORMAT.equals(parts[0])) {
            throw new IllegalArgumentException("unknown token ciphertext format");
        }
        try {
            Base64.Decoder base64 = Base64.getDecoder();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, base64.decode(parts[1])));
            cipher.updateAAD(workRef.value().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(base64.decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("could not decrypt the task token", exception);
        }
    }

    private SecretKey key() throws GeneralSecurityException {
        SecretKey current = key;
        if (current == null) {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.get().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            current = new SecretKeySpec(hmac.doFinal(KEY_LABEL), "AES");
            key = current;
        }
        return current;
    }
}

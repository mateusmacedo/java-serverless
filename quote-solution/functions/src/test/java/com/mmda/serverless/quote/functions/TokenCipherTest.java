package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mmda.serverless.quote.domain.WorkRef;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private final AtomicInteger secretReads = new AtomicInteger();
    private final TokenCipher cipher = new TokenCipher(() -> {
        secretReads.incrementAndGet();
        return "lab-secret-0123456789";
    });
    private final WorkRef workRef = WorkRef.of("q1", 1, WorkRef.Kind.INSURER, "alpha");

    @Test
    void decryptsWhatItEncryptsForTheSameWaiter() {
        String ciphertext = cipher.encrypt("task-token-value", workRef);

        assertThat(ciphertext).startsWith("v1:").doesNotContain("task-token-value");
        assertThat(cipher.decrypt(ciphertext, workRef)).isEqualTo("task-token-value");
    }

    @Test
    void sameTokenEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt("token", workRef)).isNotEqualTo(cipher.encrypt("token", workRef));
    }

    @Test
    void ciphertextOfAnotherWaiterOrKeyIsRejected() {
        String ciphertext = cipher.encrypt("token", workRef);
        WorkRef other = WorkRef.of("q1", 1, WorkRef.Kind.INSURER, "beta");

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, other)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(() -> "other-secret").decrypt(ciphertext, workRef))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain-token", workRef)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretIsReadOncePerContainer() {
        cipher.encrypt("a", workRef);
        cipher.encrypt("b", workRef);

        assertThat(secretReads).hasValue(1);
    }
}

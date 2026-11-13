package com.mmda.serverless.provider.domain.insurer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Assinatura {@code X-Signature} e envelope dos callbacks (desenho, §8). */
class CallbackSignatureTest {

    private static final byte[] SECRET = "callback-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"externalEventId\":\"bev_1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signatureIsTheHexHmacOfTheExactBody() {
        String signature = CallbackSignature.sign(SECRET, BODY);

        // NOTE: valor de referência do openssl dgst -sha256 -hmac callback-secret sobre
        // o mesmo corpo
        assertThat(signature).isEqualTo("sha256=7dd40c35ca8116bee21f54222163e9c19de42d39e9651f839a0d284f997e222b");
        assertThat(CallbackSignature.verify(SECRET, BODY, signature)).isTrue();
    }

    @Test
    void anyChangeToTheBodyTheSecretOrTheHeaderIsRejected() {
        String signature = CallbackSignature.sign(SECRET, BODY);
        byte[] otherBody = "{\"externalEventId\":\"bev_2\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(CallbackSignature.verify(SECRET, otherBody, signature)).isFalse();
        assertThat(CallbackSignature.verify("other".getBytes(StandardCharsets.UTF_8), BODY, signature)).isFalse();
        assertThat(CallbackSignature.verify(SECRET, BODY, null)).isFalse();
        assertThat(CallbackSignature.verify(SECRET, BODY, "")).isFalse();
        assertThat(CallbackSignature.verify(SECRET, BODY, signature.toUpperCase())).isFalse();
        assertThat(CallbackSignature.verify(SECRET, BODY, signature.substring("sha256=".length()))).isFalse();
        assertThat(CallbackSignature.verify(SECRET, BODY, "sha1=" + signature.substring("sha256=".length())))
                .isFalse();
    }

    @Test
    void envelopeNeedsAnEventIdACorrelationAndAResult() {
        InsurerReply result = new InsurerReply(null, "DECLINED", null, null, null, null, null, null, null,
                "RISK_NOT_ACCEPTED", null, null, null);
        String queryId = "q_" + "0123456789abcdef".repeat(2);

        assertThat(new InsurerCallback("bev_1", "beta-1", null, null, result).problems()).isEmpty();
        assertThat(new InsurerCallback("bev_1", null, queryId, null, result).problems()).isEmpty();
        assertThat(new InsurerCallback(null, "beta-1", null, null, result).problems()).hasSize(1);
        assertThat(new InsurerCallback("bev 1", "beta-1", null, null, result).problems()).hasSize(1);
        assertThat(new InsurerCallback("bev_1", null, null, null, result).problems()).hasSize(1);
        assertThat(new InsurerCallback("bev_1", "beta-1", "q_1", null, result).problems()).hasSize(1);
        assertThat(new InsurerCallback("bev_1", "beta-1", null, null, null).problems()).hasSize(1);
        assertThat(InsurerCallback.problems("bev_1", "beta-1", null, true))
                .as("the envelope read without the result only needs to know that it came").isEmpty();
        assertThat(InsurerCallback.problems("bev_1", "beta-1", null, false)).containsExactly("result is required");
    }

    @Test
    void bothHalvesNeedTheQueryIdAndAPendingEvent() {
        assertThat(new Correlation(null, Set.of("bev_1")).bothHalves()).isFalse();
        assertThat(new Correlation("q_1", null).bothHalves()).isFalse();
        assertThat(new Correlation("q_1", Set.of("bev_1")).bothHalves()).isTrue();
        assertThat(new Correlation("q_1", Set.of("bev_1")).pendingEvents()).isEqualTo(Set.copyOf(List.of("bev_1")));
    }
}

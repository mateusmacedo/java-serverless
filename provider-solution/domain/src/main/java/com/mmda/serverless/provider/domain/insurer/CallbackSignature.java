package com.mmda.serverless.provider.domain.insurer;

import com.mmda.serverless.provider.domain.Digests;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Assinatura {@code X-Signature} dos callbacks de seguradoras (desenho, §8): {@code sha256=} seguido do
 * hexadecimal minúsculo do HMAC-SHA256 do corpo, exatamente como foi enviado, com o segredo
 * {@code callback-secret} como chave.
 */
public final class CallbackSignature {

    public static final String HEADER = "X-Signature";
    private static final String PREFIX = "sha256=";
    private static final Pattern FORMAT = Pattern.compile("^sha256=[0-9a-f]{64}$");
    private static final HexFormat HEX = HexFormat.of();

    private CallbackSignature() {
    }

    public static String sign(byte[] secret, byte[] body) {
        return PREFIX + HEX.formatHex(Digests.hmacSha256(secret, body));
    }

    /**
     * Confere a assinatura em tempo constante em relação ao conteúdo: a comparação dos resumos não para no
     * primeiro byte diferente. Cabeçalho ausente ou fora do formato nunca confere.
     */
    public static boolean verify(byte[] secret, byte[] body, String header) {
        if (header == null || !FORMAT.matcher(header).matches()) {
            return false;
        }
        byte[] provided = HEX.parseHex(header.substring(PREFIX.length()));
        return MessageDigest.isEqual(Digests.hmacSha256(secret, body), provided);
    }
}

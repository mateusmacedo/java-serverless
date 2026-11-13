package com.mmda.serverless.provider.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Resumos criptográficos usados nas identidades determinísticas da provider. */
public final class Digests {

    private static final HexFormat HEX = HexFormat.of();

    private Digests() {
    }

    /** SHA-256 do texto em UTF-8, em hexadecimal minúsculo. */
    public static String sha256Hex(String value) {
        return HEX.formatHex(digest("SHA-256", value.getBytes(StandardCharsets.UTF_8)));
    }

    /** SHA-256 de bytes, em hexadecimal minúsculo. */
    public static String sha256Hex(byte[] value) {
        return HEX.formatHex(digest("SHA-256", value));
    }

    /** HMAC-SHA256 do texto em UTF-8 com a chave dada, em hexadecimal minúsculo. */
    public static String hmacSha256Hex(byte[] key, String value) {
        return HEX.formatHex(hmacSha256(key, value.getBytes(StandardCharsets.UTF_8)));
    }

    /** HMAC-SHA256 dos bytes com a chave dada. */
    public static byte[] hmacSha256(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }

    /** UUID versão 5 (RFC 9562): SHA-1 do namespace seguido do nome em UTF-8. */
    public static UUID uuidV5(UUID namespace, String name) {
        ByteBuffer input = ByteBuffer.allocate(16 + name.getBytes(StandardCharsets.UTF_8).length);
        input.putLong(namespace.getMostSignificantBits());
        input.putLong(namespace.getLeastSignificantBits());
        input.put(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest("SHA-1", input.array());
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer bits = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bits.getLong(), bits.getLong());
    }

    private static byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is not available", exception);
        }
    }
}

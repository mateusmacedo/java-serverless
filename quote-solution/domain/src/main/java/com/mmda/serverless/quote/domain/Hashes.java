package com.mmda.serverless.quote.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Hashes e identificadores determinísticos usados entre as soluções. */
public final class Hashes {

    /**
     * Namespace dos UUIDv5 da quote-solution: o namespace URL da RFC 9562. O nome é o fato (por exemplo,
     * o {@code workRef}), então o mesmo fato sempre gera o mesmo {@code eventId}.
     */
    public static final UUID EVENT_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private Hashes() {
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(digest("SHA-256", value.getBytes(StandardCharsets.UTF_8)));
    }

    /** UUID versão 5 (SHA-1, baseado em nome) de {@code name} no {@link #EVENT_NAMESPACE}. */
    public static UUID uuidV5(String name) {
        byte[] namespace = toBytes(EVENT_NAMESPACE);
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[namespace.length + nameBytes.length];
        System.arraycopy(namespace, 0, input, 0, namespace.length);
        System.arraycopy(nameBytes, 0, input, namespace.length, nameBytes.length);
        byte[] hash = digest("SHA-1", input);
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (hash[i] & 0xff);
            least = (least << 8) | (hash[i + 8] & 0xff);
        }
        return new UUID(most, least);
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (56 - 8 * i));
            bytes[i + 8] = (byte) (least >>> (56 - 8 * i));
        }
        return bytes;
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is required by the JDK", exception);
        }
    }
}

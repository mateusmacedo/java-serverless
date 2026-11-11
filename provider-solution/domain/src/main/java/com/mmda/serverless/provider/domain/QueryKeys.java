package com.mmda.serverless.provider.domain;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Chave de reuso do enriquecimento (RN03 e RN04): HMAC-SHA256, com o segredo {@code query-key-secret},
 * de {@code organizationId|provider|operation|contractVersion|vehicleRef|principalDriverRef}. Pedidos de
 * cotação a seguradoras não têm chave de reuso.
 */
public final class QueryKeys {

    private QueryKeys() {
    }

    public static String compute(byte[] secret, String organizationId, String provider, String operation,
                                 String contractVersion, String vehicleRef, String principalDriverRef) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("The query key secret is empty");
        }
        String material = String.join("|", Stream.of(organizationId, provider, operation, contractVersion,
                        vehicleRef, principalDriverRef)
                .map(part -> Objects.requireNonNull(part, "query key part").strip())
                .toList());
        return Digests.hmacSha256Hex(secret, material);
    }
}

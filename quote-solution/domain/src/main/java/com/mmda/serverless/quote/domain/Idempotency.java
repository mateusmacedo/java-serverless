package com.mmda.serverless.quote.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Idempotência das mutações da API (§4 e §8 do desenho). A chave é única por organização, operação,
 * escopo e {@code Idempotency-Key}; o {@code requestHash} é o SHA-256 do body em JSON canônico.
 */
public final class Idempotency {

    /** Escopo das operações sem cotação existente, como a criação. */
    public static final String NO_SCOPE = "-";

    private static final Pattern KEY_FORMAT = Pattern.compile("[\\x21-\\x7E]{1,200}");

    private Idempotency() {
    }

    public enum Operation {
        CREATE_QUOTE,
        CREATE_VERSION,
        SELECT_OFFER,
        RECONCILE
    }

    /** Resultado da comparação com um registro que já existe para a mesma chave. */
    public enum Decision {
        /** Mesmo body: devolve a mesma identidade, sem novo efeito. */
        REPLAY,
        /** Body diferente: 409 {@code IDEMPOTENCY_CONFLICT}, sem alterar o recurso. */
        CONFLICT
    }

    /** Chave enviada no header: ASCII visível, sem espaços, até 200 caracteres. */
    public static boolean isValidKey(String key) {
        return key != null && KEY_FORMAT.matcher(key).matches();
    }

    public static String requestHash(String body) {
        return Hashes.sha256Hex(CanonicalJson.canonicalize(body));
    }

    public static Decision compare(String storedHash, String requestHash) {
        return Objects.equals(storedHash, requestHash) ? Decision.REPLAY : Decision.CONFLICT;
    }

    public record Key(String organizationId, Operation operation, String scope, String key) {

        public Key {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(scope, "scope");
            if (!isValidKey(key)) {
                throw new IllegalArgumentException("invalid Idempotency-Key");
            }
        }

        public String pk() {
            return "IDEMPOTENCY#" + organizationId + "#" + operation.name() + "#" + scope + "#" + key;
        }
    }
}

package com.mmda.serverless.provider.domain.insurer;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.ErrorClasses;

/**
 * Situação de um pedido na API de situação de uma seguradora assíncrona
 * ({@code GET /sim/insurers/beta/quotes/
 * {ref}}, desenho, §10), consultada pela reconciliação de uma consulta
 * {@code UNKNOWN} sem repetir a chamada. O
 * {@code result}, em {@code COMPLETED}, tem o formato do callback.
 */
public record InsurerStatus(
        String providerRequestId,
        String clientReference,
        String status,
        String receivedAt,
        String completedAt,
        InsurerReply result) {

    public static final String COMPLETED = "COMPLETED";
    public static final String PROCESSING = "PROCESSING";

    /** O que a consulta da situação diz à reconciliação. */
    public sealed interface Verdict {
    }

    /** A seguradora concluiu: o resultado é classificado como o callback (§9). */
    public record Completed(InsurerVerdict verdict, String completedAt) implements Verdict {
    }

    /**
     * A seguradora ainda processa; a consulta continua {@code UNKNOWN} até o
     * próximo ciclo.
     */
    public record Pending() implements Verdict {
    }

    /**
     * A seguradora não conhece a referência: nenhuma conclusão automática é segura.
     */
    public record Unrecognized() implements Verdict {
    }

    /** Falha da própria consulta da situação; vale tentar no próximo ciclo. */
    public record Unreachable(String errorClass) implements Verdict {
    }

    public static Verdict classify(CallOutcome<InsurerStatus> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<InsurerStatus> notSent -> new Unreachable(notSent.errorClass());
            case CallOutcome.NoResponse<InsurerStatus> noResponse -> new Unreachable(noResponse.errorClass());
            case CallOutcome.Responded<InsurerStatus> responded -> classifyResponse(responded);
        };
    }

    private static Verdict classifyResponse(CallOutcome.Responded<InsurerStatus> responded) {
        if (responded.statusCode() == 404) {
            return new Unrecognized();
        }
        InsurerStatus status = responded.reply();
        if (responded.statusCode() / 100 != 2 || status == null || status.status() == null) {
            return new Unreachable(responded.statusCode() / 100 == 2
                    ? ErrorClasses.INVALID_PROVIDER_RESPONSE
                    : ErrorClasses.http(responded.statusCode()));
        }
        return switch (status.status()) {
            case COMPLETED -> new Completed(InsurerProtocol.classifyCallback(status.result()), status.completedAt());
            case PROCESSING -> new Pending();
            default -> new Unreachable(ErrorClasses.INVALID_PROVIDER_RESPONSE);
        };
    }
}

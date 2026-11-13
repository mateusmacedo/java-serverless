package com.mmda.serverless.provider.domain.insurer;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.Decimals;
import com.mmda.serverless.provider.domain.ErrorClasses;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.DateTimeException;
import java.util.List;
import java.util.Optional;

/**
 * Classifica a resposta de uma seguradora pela tabela do §9 do desenho e
 * normaliza a oferta. Resposta
 * incompleta ou ilegível de quem já recebeu o pedido é {@code UNAVAILABLE} sem
 * retry, porque repetir
 * poderia gerar uma segunda cotação no fornecedor.
 */
public final class InsurerProtocol {

    public static final String DEFAULT_DECLINE_REASON = "DECLINED_BY_INSURER";
    public static final String NEEDS_INFORMATION_REASON = "INSURER_NEEDS_INFORMATION";

    private InsurerProtocol() {
    }

    public static InsurerVerdict classify(CallOutcome<InsurerReply> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<InsurerReply> notSent -> new InsurerVerdict.Transient(notSent.errorClass());
            case CallOutcome.NoResponse<InsurerReply> ignored ->
                new InsurerVerdict.Unknown(ErrorClasses.PROVIDER_NO_RESPONSE);
            case CallOutcome.Responded<InsurerReply> responded -> classifyResponse(responded);
        };
    }

    /**
     * Resultado entregue por callback: classificado como a resposta {@code 200} da
     * seguradora síncrona. Sem
     * resultado legível, é {@code UNAVAILABLE} com
     * {@code INVALID_PROVIDER_RESPONSE}.
     */
    public static InsurerVerdict classifyCallback(InsurerReply result) {
        return result == null ? invalidResponse() : classifyDecision(result);
    }

    private static InsurerVerdict classifyResponse(CallOutcome.Responded<InsurerReply> responded) {
        int status = responded.statusCode();
        InsurerReply reply = responded.reply();
        if (status == 202) {
            // WHY: um providerRequestId fora do formato do callback faria todo callback
            // dele ser recusado com 400,
            // e a consulta esperaria em voo um resultado que nunca seria aceito
            return reply != null && InsurerCallback.isExternalId(reply.providerRequestId())
                    ? new InsurerVerdict.Accepted(reply.providerRequestId())
                    : invalidResponse();
        }
        if (status == 200 || status == 201) {
            return reply == null ? invalidResponse() : classifyDecision(reply);
        }
        if (status == 429 || status >= 500) {
            return new InsurerVerdict.Transient(ErrorClasses.http(status));
        }
        if (status == 401 || status == 403) {
            return new InsurerVerdict.Unavailable(ErrorClasses.PROVIDER_AUTHENTICATION_FAILED);
        }
        return new InsurerVerdict.Unavailable(status >= 400
                ? ErrorClasses.PROVIDER_REJECTED_REQUEST
                : ErrorClasses.INVALID_PROVIDER_RESPONSE);
    }

    private static InsurerVerdict classifyDecision(InsurerReply reply) {
        String outcome = reply.outcome() == null ? "" : reply.outcome();
        return switch (outcome) {
            case "OFFER" -> normalizeOffer(reply)
                    .<InsurerVerdict>map(InsurerVerdict.Quoted::new)
                    .orElseGet(InsurerProtocol::invalidResponse);
            case "DECLINED" -> new InsurerVerdict.Declined(
                    reply.declineCode() == null ? DEFAULT_DECLINE_REASON : reply.declineCode());
            case "PENDING_INFORMATION" -> normalizePending(reply)
                    .<InsurerVerdict>map(
                            fields -> new InsurerVerdict.NeedsInformation(NEEDS_INFORMATION_REASON, fields))
                    .orElseGet(InsurerProtocol::invalidResponse);
            default -> invalidResponse();
        };
    }

    private static InsurerVerdict invalidResponse() {
        return new InsurerVerdict.Unavailable(ErrorClasses.INVALID_PROVIDER_RESPONSE);
    }

    /**
     * Oferta nos formatos do contrato; valor fora do formato é resposta ilegível,
     * não oferta.
     */
    private static Optional<Offer> normalizeOffer(InsurerReply reply) {
        InsurerReply.Premium premium = reply.premium();
        if (reply.offerNumber() == null || reply.insurerName() == null || reply.expiresAt() == null
                || premium == null || premium.currency() == null || !Decimals.isDecimal(premium.total())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Offer(
                    reply.offerNumber(),
                    reply.insurerName(),
                    premium.total(),
                    premium.currency(),
                    nullToEmpty(reply.paymentOptions()).stream()
                            .map(option -> new Offer.Installment(option.installments(), option.installmentAmount()))
                            .toList(),
                    nullToEmpty(reply.coverages()).stream()
                            .map(coverage -> new Offer.Coverage(coverage.code(), coverage.description(),
                                    coverage.limit()))
                            .toList(),
                    nullToEmpty(reply.deductibles()).stream()
                            .map(deductible -> new Offer.Deductible(deductible.coverage(), deductible.amount()))
                            .toList(),
                    Timestamps.format(Timestamps.parse(reply.expiresAt())),
                    null));
        } catch (IllegalArgumentException | NullPointerException | DateTimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<List<MissingField>> normalizePending(InsurerReply reply) {
        List<InsurerReply.PendingField> pending = nullToEmpty(reply.pendingFields());
        if (pending.isEmpty() || pending.stream().anyMatch(field -> field.field() == null || field.code() == null)) {
            return Optional.empty();
        }
        return Optional.of(pending.stream().map(field -> new MissingField(field.field(), field.code())).toList());
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}

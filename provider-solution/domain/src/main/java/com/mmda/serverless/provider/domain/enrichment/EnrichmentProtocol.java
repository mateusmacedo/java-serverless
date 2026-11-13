package com.mmda.serverless.provider.domain.enrichment;

import com.mmda.serverless.provider.domain.CallOutcome;
import com.mmda.serverless.provider.domain.Decimals;
import com.mmda.serverless.provider.domain.ErrorClasses;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Classifica a resposta do provedor de enriquecimento pela tabela do §9 do
 * desenho e normaliza o perfil.
 * Só um 404 com {@code code = NOT_FOUND} e referências conhecidas em
 * {@code notFoundRefs} é
 * {@code NOT_FOUND}; qualquer outra falha é {@code FAILED} (RN07).
 */
public final class EnrichmentProtocol {

    public static final String NOT_FOUND_CODE = "NOT_FOUND";
    public static final Set<String> DECLARABLE_REFS = Set.of("vehicleRef", "principalDriverRef");

    private EnrichmentProtocol() {
    }

    public static EnrichmentVerdict classify(CallOutcome<EnrichmentReply> outcome) {
        return switch (outcome) {
            case CallOutcome.NotSent<EnrichmentReply> notSent -> new EnrichmentVerdict.Transient(notSent.errorClass());
            case CallOutcome.NoResponse<EnrichmentReply> ignored ->
                new EnrichmentVerdict.Unknown(ErrorClasses.PROVIDER_NO_RESPONSE);
            case CallOutcome.Responded<EnrichmentReply> responded -> classifyResponse(responded);
        };
    }

    private static EnrichmentVerdict classifyResponse(CallOutcome.Responded<EnrichmentReply> responded) {
        int status = responded.statusCode();
        EnrichmentReply reply = responded.reply();
        if (status == 200) {
            return normalize(reply)
                    .<EnrichmentVerdict>map(EnrichmentVerdict.Found::new)
                    .orElse(new EnrichmentVerdict.Failed(ErrorClasses.INVALID_PROVIDER_RESPONSE));
        }
        if (status == 404) {
            return declaredNotFound(reply)
                    ? new EnrichmentVerdict.NotFound(reply.notFoundRefs())
                    : new EnrichmentVerdict.Failed(ErrorClasses.UNDECLARED_NOT_FOUND);
        }
        if (status == 429 || status >= 500) {
            return new EnrichmentVerdict.Transient(ErrorClasses.http(status));
        }
        if (status == 401 || status == 403) {
            return new EnrichmentVerdict.Failed(ErrorClasses.PROVIDER_AUTHENTICATION_FAILED);
        }
        if (status == 400 || status == 422) {
            return new EnrichmentVerdict.Failed(ErrorClasses.INVALID_PAYLOAD);
        }
        return new EnrichmentVerdict.Failed(status >= 400
                ? ErrorClasses.PROVIDER_REJECTED_REQUEST
                : ErrorClasses.INVALID_PROVIDER_RESPONSE);
    }

    private static boolean declaredNotFound(EnrichmentReply reply) {
        if (reply == null || !NOT_FOUND_CODE.equals(reply.code())) {
            return false;
        }
        List<String> refs = reply.notFoundRefs();
        return refs != null && !refs.isEmpty() && DECLARABLE_REFS.containsAll(refs);
    }

    private static Optional<EnrichmentProfile> normalize(EnrichmentReply reply) {
        if (reply == null || reply.vehicle() == null || reply.driver() == null) {
            return Optional.empty();
        }
        EnrichmentReply.Vehicle vehicle = reply.vehicle();
        EnrichmentReply.Driver driver = reply.driver();
        if (vehicle.ref() == null || vehicle.modelYear() == null || !Decimals.isDecimal(vehicle.marketValue())
                || driver.ref() == null || driver.licenseYears() == null || driver.claimsLast5Years() == null) {
            return Optional.empty();
        }
        return Optional.of(new EnrichmentProfile(
                new EnrichmentProfile.Vehicle(vehicle.ref(), vehicle.make(), vehicle.model(), vehicle.modelYear(),
                        vehicle.marketValue()),
                new EnrichmentProfile.Driver(driver.ref(), driver.licenseYears(), driver.claimsLast5Years())));
    }
}

package com.mmda.serverless.provider.domain.insurer;

import com.mmda.serverless.provider.domain.EventIds;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code detail} do {@code InsurerResultRecorded} (desenho, §3); o
 * {@code eventId} é o UUIDv5 do
 * {@code queryId}. {@code offer} só existe em {@code QUOTED} e
 * {@code missingFields} só em
 * {@code NEEDS_INFORMATION}; {@code attemptRef} falta quando a provider
 * concluiu sem chamar.
 */
public record InsurerResultRecorded(
        String eventId,
        String occurredAt,
        String schemaVersion,
        String workRef,
        String quoteId,
        int version,
        String insurerId,
        String queryId,
        String attemptRef,
        InsurerResultStatus status,
        String reasonCode,
        List<MissingField> missingFields,
        Offer offer) {

    public static final String DETAIL_TYPE = "InsurerResultRecorded";
    public static final String SCHEMA_VERSION = "1";

    public InsurerResultRecorded {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(status, "status");
        if ((status == InsurerResultStatus.QUOTED) != (offer != null)) {
            throw new IllegalArgumentException("offer is present only when QUOTED");
        }
        if ((status == InsurerResultStatus.NEEDS_INFORMATION) != (missingFields != null)) {
            throw new IllegalArgumentException("missingFields is present only when NEEDS_INFORMATION");
        }
        if (status != InsurerResultStatus.QUOTED && reasonCode == null) {
            throw new IllegalArgumentException("reasonCode is required when the insurer did not quote");
        }
        missingFields = missingFields == null ? null : List.copyOf(missingFields);
    }

    public static InsurerResultRecorded of(InsurerQuoteRequest request, String queryId, String attemptRef,
            Outcome outcome, String occurredAt) {
        return of(new Origin(request.workRef(), request.quoteId(), request.version(), request.insurerId()), queryId,
                attemptRef, outcome, occurredAt);
    }

    /**
     * Evento a partir da consulta gravada, como no callback, que não tem o pedido
     * em mãos.
     */
    public static InsurerResultRecorded of(Origin origin, String queryId, String attemptRef, Outcome outcome,
            String occurredAt) {
        return new InsurerResultRecorded(
                EventIds.insurerResultRecorded(queryId),
                occurredAt,
                SCHEMA_VERSION,
                origin.workRef(),
                origin.quoteId(),
                origin.version(),
                origin.insurerId(),
                queryId,
                attemptRef,
                outcome.status(),
                outcome.reasonCode(),
                outcome.missingFields(),
                outcome.offer());
    }

    /** Pedido da quote que abriu a consulta, como fica gravado na {@code QUERY}. */
    public record Origin(String workRef, String quoteId, int version, String insurerId) {
    }

    /** Resultado terminal da seguradora, como fica na evidência e no evento. */
    public record Outcome(InsurerResultStatus status, String reasonCode, List<MissingField> missingFields,
            Offer offer) {

        /**
         * Resultado de uma classificação terminal; a oferta recebe a chave da resposta
         * bruta.
         */
        public static Outcome of(InsurerVerdict verdict, String rawResponseRef) {
            return switch (verdict) {
                case InsurerVerdict.Quoted quoted -> quoted(quoted.offer().withRawResponseRef(rawResponseRef));
                case InsurerVerdict.Declined declined -> declined(declined.reasonCode());
                case InsurerVerdict.NeedsInformation needs ->
                    needsInformation(needs.reasonCode(), needs.missingFields());
                case InsurerVerdict.Unavailable unavailable -> unavailable(unavailable.reasonCode());
                default -> throw new IllegalArgumentException("Not a terminal insurer result: " + verdict);
            };
        }

        /**
         * Conteúdo normalizado da evidência: a oferta, ou o motivo com os campos
         * pendentes.
         */
        public Object evidenceBody() {
            if (offer != null) {
                return offer;
            }
            return missingFields == null
                    ? Map.of("reasonCode", reasonCode)
                    : Map.of("reasonCode", reasonCode, "missingFields", missingFields);
        }

        public static Outcome quoted(Offer offer) {
            return new Outcome(InsurerResultStatus.QUOTED, null, null, offer);
        }

        public static Outcome declined(String reasonCode) {
            return new Outcome(InsurerResultStatus.DECLINED, reasonCode, null, null);
        }

        public static Outcome needsInformation(String reasonCode, List<MissingField> missingFields) {
            return new Outcome(InsurerResultStatus.NEEDS_INFORMATION, reasonCode, missingFields, null);
        }

        public static Outcome unavailable(String reasonCode) {
            return new Outcome(InsurerResultStatus.UNAVAILABLE, reasonCode, null, null);
        }
    }
}

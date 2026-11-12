package com.mmda.serverless.provider.domain.insurer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Callback de seguradora assíncrona, no contrato {@code docs/api/provider-solution.openapi.yaml}. O
 * {@code externalEventId} se repete na reentrega; o {@code clientReference} é o {@code queryId} do pedido. O
 * {@code result} tem o formato da resposta síncrona e é classificado pela mesma tabela do §9.
 */
public record InsurerCallback(
        String externalEventId,
        String providerRequestId,
        String clientReference,
        String sentAt,
        InsurerReply result) {

    private static final Pattern EXTERNAL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern CLIENT_REFERENCE = Pattern.compile("^q_[0-9a-f]{32}$");

    /**
     * Problemas do envelope, que impedem guardar o callback na inbox; vazio quando ele é aceitável. O conteúdo
     * de {@code result} não entra aqui: um resultado ilegível é {@code UNAVAILABLE}, como na resposta síncrona.
     */
    public List<String> problems() {
        return problems(externalEventId, providerRequestId, clientReference, result != null);
    }

    /**
     * Os mesmos problemas, para o envelope lido sem o {@code result}: {@code hasResult} diz só se ele veio, porque
     * o conteúdo dele é classificado no processamento e nunca recusa o callback.
     */
    public static List<String> problems(String externalEventId, String providerRequestId, String clientReference,
                                        boolean hasResult) {
        List<String> problems = new ArrayList<>();
        if (!isExternalId(externalEventId)) {
            problems.add("externalEventId is required and must match " + EXTERNAL_ID.pattern());
        }
        if (providerRequestId != null && !isExternalId(providerRequestId)) {
            problems.add("providerRequestId must match " + EXTERNAL_ID.pattern());
        }
        if (clientReference != null && !CLIENT_REFERENCE.matcher(clientReference).matches()) {
            problems.add("clientReference must match " + CLIENT_REFERENCE.pattern());
        }
        if (providerRequestId == null && clientReference == null) {
            problems.add("providerRequestId or clientReference is required");
        }
        if (!hasResult) {
            problems.add("result is required");
        }
        return problems;
    }

    /** Formato de {@code externalEventId} e {@code providerRequestId}, que também compõem chaves da tabela. */
    public static boolean isExternalId(String value) {
        return value != null && EXTERNAL_ID.matcher(value).matches();
    }
}

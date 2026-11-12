package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.enrichment.EnrichmentReply;
import com.mmda.serverless.provider.domain.enrichment.EnrichmentRequest;
import com.mmda.serverless.provider.domain.insurer.InsurerQuoteRequest;
import com.mmda.serverless.provider.domain.insurer.InsurerReply;
import com.mmda.serverless.provider.domain.insurer.InsurerStatus;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Adaptadores dos protocolos dos fornecedores simulados. O {@code clientReference} enviado é o
 * {@code queryId}, para o callback e o contador do {@code vehicle-flaky} acharem a consulta (§10).
 */
@Component
class ProviderClients {

    static final String ENRICHMENT_PATH = "/sim/enrichment/profiles";

    private final ProviderHttp http;
    private final EventJson json;
    private final ProviderProperties properties;

    ProviderClients(ProviderHttp http, EventJson json, ProviderProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    ProviderExchange<EnrichmentReply> fetchProfile(String queryId, EnrichmentRequest.Params params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientReference", queryId);
        body.put("vehicleRef", params.vehicleRef());
        body.put("principalDriverRef", params.principalDriverRef());
        return ProviderExchange.of(http.postJson(ENRICHMENT_PATH, json.write(body)), json, EnrichmentReply.class);
    }

    /** Rota síncrona da seguradora; vazio quando ela não é atendida por este worker. */
    Optional<String> insurerPath(String insurerId) {
        return properties.supportedInsurers().contains(insurerId)
                ? Optional.of("/sim/insurers/" + insurerId + "/quotes")
                : Optional.empty();
    }

    /**
     * Situação de um pedido já enviado, pela referência dele no fornecedor ({@code providerRequestId} ou
     * {@code clientReference}). WHY: é uma leitura; a reconciliação nunca repete o {@code POST} da cotação (§8).
     */
    ProviderExchange<InsurerStatus> insurerStatus(String insurerId, String reference) {
        String path = "/sim/insurers/" + insurerId + "/quotes/" + URLEncoder.encode(reference, StandardCharsets.UTF_8);
        return ProviderExchange.of(http.getJson(path), json, InsurerStatus.class);
    }

    ProviderExchange<InsurerReply> requestQuote(String path, String queryId, InsurerQuoteRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientReference", queryId);
        body.put("risk", request.risk());
        body.put("enrichment", request.enrichment());
        return ProviderExchange.of(http.postJson(path, json.write(body)), json, InsurerReply.class);
    }
}

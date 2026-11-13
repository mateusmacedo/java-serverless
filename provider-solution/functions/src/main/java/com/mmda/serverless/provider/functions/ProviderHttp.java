package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.CallOutcome;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import org.springframework.stereotype.Component;

/**
 * POST JSON para os fornecedores, sob {@code PROVIDERS_BASE_URL}. Separa o que falhou antes do envio
 * (repetível) do que pode ter chegado ao fornecedor sem resposta (ambíguo), como pede o §9 do desenho.
 */
@Component
class ProviderHttp {

    static final String CONNECT_TIMEOUT = "CONNECT_TIMEOUT";
    static final String CONNECTION_REFUSED = "CONNECTION_REFUSED";
    static final String READ_TIMEOUT = "READ_TIMEOUT";
    static final String IO_ERROR = "IO_ERROR";
    static final String INTERRUPTED = "INTERRUPTED";

    private final HttpClient httpClient;
    private final ProviderProperties properties;

    ProviderHttp(HttpClient httpClient, ProviderProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    CallOutcome<String> postJson(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(stripTrailingSlash(properties.providersBaseUrl()) + path))
                .timeout(properties.httpTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /** Leitura sem efeito no fornecedor, como a API de situação da seguradora assíncrona (§10). */
    CallOutcome<String> getJson(String path) {
        return send(HttpRequest.newBuilder(URI.create(stripTrailingSlash(properties.providersBaseUrl()) + path))
                .timeout(properties.httpTimeout())
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private CallOutcome<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new CallOutcome.Responded<>(response.statusCode(), response.body());
        } catch (HttpConnectTimeoutException exception) {
            return new CallOutcome.NotSent<>(CONNECT_TIMEOUT);
        } catch (HttpTimeoutException exception) {
            return new CallOutcome.NoResponse<>(READ_TIMEOUT);
        } catch (ConnectException exception) {
            return new CallOutcome.NotSent<>(CONNECTION_REFUSED);
        } catch (IOException exception) {
            return new CallOutcome.NoResponse<>(IO_ERROR);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CallOutcome.NoResponse<>(INTERRUPTED);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

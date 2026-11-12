package com.mmda.serverless.quote.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Cliente da API da quote no e2e (T22): toda troca é conferida contra o {@code contracts/openapi.yaml}, o mesmo
 * arquivo que o frontend consome. A resposta é sempre conferida; a requisição, só quando a API a aceita, porque os
 * casos de erro a fazem inválida de propósito. Guarda as operações do contrato exercitadas.
 */
final class ContractClient {

    static final Path SPECIFICATION = Path.of("../../contracts/openapi.yaml");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final OpenApiInteractionValidator validator;
    private final List<Operation> operations;
    private final Set<String> exercised = ConcurrentHashMap.newKeySet();
    private final Set<String> succeeded = ConcurrentHashMap.newKeySet();

    private record Operation(String method, String template, String operationId, Pattern pattern) {
    }

    ContractClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.validator = OpenApiInteractionValidator
                .createForSpecificationUrl(SPECIFICATION.toAbsolutePath().normalize().toUri().toString())
                .withBasePathOverride("/")
                .build();
        this.operations = operationsOf(SPECIFICATION);
    }

    /** {@code operationId} de todas as operações do contrato. */
    Set<String> operationIds() {
        Set<String> ids = new TreeSet<>();
        operations.forEach(operation -> ids.add(operation.operationId()));
        return ids;
    }

    /** {@code operationId} das operações que o e2e já exercitou nesta execução, com qualquer status. */
    Set<String> exercised() {
        return new TreeSet<>(exercised);
    }

    /** {@code operationId} das operações com ao menos uma resposta 2xx conferida contra o contrato. */
    Set<String> succeeded() {
        return new TreeSet<>(succeeded);
    }

    /**
     * Envia a requisição e confere a troca contra o contrato. {@code token} nulo vai sem {@code Authorization};
     * {@code pathAndQuery} começa em {@code /v1}.
     */
    HttpResponse<String> send(String token, String method, String pathAndQuery, Map<String, String> headers,
            String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofMinutes(1))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(method + " " + pathAndQuery + " failed", exception);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + pathAndQuery + " interrupted", interrupted);
        }
        check(token, method, pathAndQuery, headers, body, response);
        return response;
    }

    private void check(String token, String method, String pathAndQuery, Map<String, String> headers, String body,
            HttpResponse<String> response) {
        URI uri = URI.create(pathAndQuery);
        String path = uri.getRawPath();
        SimpleRequest.Builder request = new SimpleRequest.Builder(method, path);
        if (token != null) {
            request.withAuthorization("Bearer " + token);
        }
        headers.forEach((name, value) -> {
            if (name.equalsIgnoreCase("Content-Type")) {
                request.withContentType(value);
            } else {
                request.withHeader(name, value);
            }
        });
        if (body != null) {
            request.withBody(body);
        }
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int equals = pair.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
                request.withQueryParam(name, value);
            }
        }
        SimpleResponse.Builder answer = SimpleResponse.Builder.status(response.statusCode()).withBody(response.body());
        response.headers().map().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Content-Type")) {
                answer.withContentType(values.getFirst());
            } else {
                answer.withHeader(name, values);
            }
        });
        // NOTE: nos erros a requisição pode ser inválida de propósito; só a resposta precisa seguir o contrato
        ValidationReport report = response.statusCode() < 300
                ? validator.validate(request.build(), answer.build())
                : validator.validateResponse(path, Request.Method.valueOf(method.toUpperCase(Locale.ROOT)),
                        answer.build());
        List<String> errors = report.getMessages().stream()
                .filter(message -> message.getLevel() == ValidationReport.Level.ERROR)
                .map(ValidationReport.Message::toString)
                .toList();
        assertThat(errors).as("%s %s -> %d %s", method, pathAndQuery, response.statusCode(), response.body())
                .isEmpty();
        operationOf(method, path).ifPresent(operationId -> {
            exercised.add(operationId);
            if (response.statusCode() < 300) {
                succeeded.add(operationId);
            }
        });
    }

    /** A operação de literal mais longo vence: {@code /v1/operations/issues} antes de {@code /v1/operations/{id}}. */
    private Optional<String> operationOf(String method, String path) {
        return operations.stream()
                .filter(operation -> operation.method().equalsIgnoreCase(method))
                .filter(operation -> operation.pattern().matcher(path).matches())
                .max(Comparator.comparingInt(operation -> operation.template().replaceAll("\\{[^}]+}", "").length()))
                .map(Operation::operationId);
    }

    @SuppressWarnings("unchecked")
    private static List<Operation> operationsOf(Path specification) {
        Map<String, Object> document;
        try {
            document = new Yaml().load(Files.readString(specification));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + specification, exception);
        }
        List<Operation> found = new ArrayList<>();
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        paths.forEach((template, item) -> ((Map<String, Object>) item).forEach((method, operation) -> {
            if (METHODS.contains(method)) {
                String regex = "^" + template.replaceAll("\\{[^}]+}", "[^/]+") + "$";
                found.add(new Operation(method, template,
                        (String) ((Map<String, Object>) operation).get("operationId"), Pattern.compile(regex)));
            }
        }));
        return found;
    }
}

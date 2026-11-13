package com.mmda.serverless.provider.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mmda.serverless.provider.domain.insurer.CallbackSignature;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/providers/{provider}/callbacks} (§8), no contrato
 * {@code contracts/provider-solution/openapi.yaml}.
 * A assinatura é conferida sobre os bytes recebidos antes de qualquer leitura
 * ou escrita: sem ela, {@code 401}.
 * O resultado gravado responde {@code 200}; o callback guardado à espera do
 * encontro no {@code CORR#}, {@code 202}.
 * Só o envelope é validado aqui: o conteúdo de {@code result} é classificado no
 * processamento.
 */
@RestController
class CallbackController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CallbackController.class);
    /**
     * WHY: o corpo inteiro vai para o item {@code INBOX#}, e o DynamoDB recusa
     * itens acima de 400 KB; sem o
     * limite, o callback grande receberia {@code 503} e seria reenviado para
     * sempre.
     */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final CallbackSecret secret;
    private final CallbackInbox inbox;
    private final ApiProperties properties;
    private final ObjectMapper objectMapper;

    CallbackController(CallbackSecret secret, CallbackInbox inbox, ApiProperties properties,
            ObjectMapper objectMapper) {
        this.secret = secret;
        this.inbox = inbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/v1/providers/{provider}/callbacks", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> receive(@PathVariable String provider,
            @RequestHeader(name = CallbackSignature.HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        byte[] received = body == null ? new byte[0] : body;
        boolean knownProvider = properties.providers().contains(provider);
        if (!CallbackSignature.verify(secret.value(), received, signature)) {
            // WHY: o caminho vem de quem não provou nada; só um provedor da lista vai para
            // o log
            LOGGER.warn("Callback rejected by its signature: provider={}", knownProvider ? provider : "<unknown>");
            return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "X-Signature does not match the body");
        }
        if (!knownProvider) {
            return error(HttpStatus.NOT_FOUND, "UNKNOWN_PROVIDER", "No callbacks are expected from " + provider);
        }
        if (received.length > MAX_BODY_BYTES) {
            return error(HttpStatus.CONTENT_TOO_LARGE, "CALLBACK_TOO_LARGE",
                    "The body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode json;
        CallbackEnvelope callback;
        try {
            json = objectMapper.readTree(received);
            callback = json != null && json.isObject() ? objectMapper.treeToValue(json, CallbackEnvelope.class) : null;
        } catch (JacksonException | IllegalArgumentException exception) {
            callback = null;
            json = null;
        }
        if (callback == null) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK", "The body is not a callback in JSON");
        }
        List<String> problems = callback.problems(json.hasNonNull("result"));
        if (!problems.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK", String.join("; ", problems));
        }
        CallbackInbox.Receipt receipt = inbox.receive(provider, callback, new String(received, StandardCharsets.UTF_8));
        return ResponseEntity.status(receipt.processed() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(receipt);
    }

    /**
     * Falha da AWS antes de a inbox ficar consistente: o fornecedor reenvia o mesmo
     * callback.
     */
    @ExceptionHandler(SdkException.class)
    ResponseEntity<Object> unavailable(SdkException exception) {
        LOGGER.warn("Callback not stored, the provider should send it again: {}", exception.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Send the same callback again");
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(code, message));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorBody(String code, String message) {
    }
}

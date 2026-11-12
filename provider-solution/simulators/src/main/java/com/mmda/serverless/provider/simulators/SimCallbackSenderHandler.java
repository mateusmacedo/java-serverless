package com.mmda.serverless.provider.simulators;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrada da Lambda {@code simCallbackSender}, consumidora da fila {@code sim-callbacks} com
 * {@code ReportBatchItemFailures}: só a mensagem que falhou volta à fila. Lê o evento SQS como JSON cru, como o
 * {@code resume} da quote.
 * <p>
 * WHY: sem contexto Spring. No Floci, a partida a frio do sender e a da API somam-se ao atraso do callback
 * dentro da espera da seguradora (20 s no local), e o contexto dos simuladores custava cerca de 4,6 s a mais.
 */
public class SimCallbackSenderHandler implements RequestStreamHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimCallbackSenderHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SimCallbackSender sender;

    public SimCallbackSenderHandler() {
        this(Holder.SENDER);
    }

    SimCallbackSenderHandler(SimCallbackSender sender) {
        this.sender = sender;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        JsonNode event = JSON.readTree(input);
        List<Map<String, String>> failures = new ArrayList<>();
        for (JsonNode record : event.path("Records")) {
            String messageId = record.path("messageId").asString();
            try {
                sender.send(record.path("body").asString());
            } catch (RuntimeException exception) {
                LOGGER.warn("Callback not delivered, the message goes back to the queue: messageId={} cause={}",
                        messageId, exception.getMessage());
                failures.add(Map.of("itemIdentifier", messageId));
            }
        }
        JSON.writeValue(output, Map.of("batchItemFailures", failures));
    }

    /** Configuração pelas variáveis de ambiente da Lambda; os testes as passam como propriedades de sistema. */
    private static final class Holder {

        static final SimCallbackSender SENDER = new SimCallbackSender(
                new CallbackSecret(SecretsManagerClient.builder().httpClientBuilder(AwsCrtHttpClient.builder()).build(),
                        setting("CALLBACK_SECRET_ID")),
                setting("CALLBACKS_BASE_URL"),
                Clock.systemUTC());

        private static String setting(String name) {
            String value = System.getenv(name);
            if (value == null) {
                value = System.getProperty(name);
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is not configured");
            }
            return value;
        }
    }
}

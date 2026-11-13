package com.mmda.serverless.receipt.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * O fixture é o exemplo do {@code RequestCompleted} em {@code contracts/sample-solution/asyncapi.yaml}, capturado
 * no Floci. Se a sample-solution mudar o evento, o exemplo muda e este teste acusa a quebra.
 */
class CompletedRequestContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsTheDetailOfTheDocumentedEvent() throws Exception {
        JsonNode event;
        try (InputStream json = getClass().getResourceAsStream("/request-completed-event.json")) {
            event = objectMapper.readTree(json);
        }

        CompletedRequest request = objectMapper.treeToValue(event.get("detail"), CompletedRequest.class);

        assertThat(request).isEqualTo(new CompletedRequest(
                "8b2f674a-770f-4982-a3d1-c0141eeed347",
                "ab877cff32c371f6472dbd3ccee3a1aee327d49a71135bea1073a5fe050fd9f7",
                31L));
    }
}

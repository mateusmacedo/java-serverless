package com.mmda.serverless.sample.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

final class TestFixtures {

    static final FunctionsProperties PROPERTIES = new FunctionsProperties(
            "payloads-bucket",
            "requests-table",
            "arn:aws:sns:us-east-1:000000000000:notifications",
            "events-bus",
            "dead-letter-queue",
            "arn:aws:states:us-east-1:000000000000:stateMachine:request-flow",
            "/sample/max-payload-bytes",
            "sample/signing-key",
            3);

    private TestFixtures() {
    }

    static RequestContext newRequest(int attempt) {
        return new RequestContext("req-1", "requests/req-1.json", attempt, null, null);
    }

    static RequestContext validatedRequest() {
        return new RequestContext("req-1", "requests/req-1.json", 1, "abc123", 42L);
    }

    /** Mesmo comportamento do ObjectMapper do Spring Boot, que ignora propriedades desconhecidas. */
    static ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}

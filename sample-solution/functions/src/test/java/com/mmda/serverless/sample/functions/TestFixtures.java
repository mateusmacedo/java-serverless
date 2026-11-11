package com.mmda.serverless.sample.functions;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
        return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }
}

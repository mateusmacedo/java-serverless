package com.mmda.serverless.quote.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class CreateQuoteRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Um caractere fora do BMP: conta 1 no maxLength do contrato e ocupa 2
     * {@code char} em Java.
     */
    private static final String CAR = "🚗";

    @Test
    void referenceAtTheContractLimitCountsUnicodeCharacters() throws Exception {
        CreateQuoteRequest request = CreateQuoteRequest.parse(withApplicantRef(CAR.repeat(128)));

        assertThat(request.applicantRef()).isEqualTo(CAR.repeat(128));
    }

    @Test
    void referenceAboveTheContractLimitIsAFieldError() throws Exception {
        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> CreateQuoteRequest.parse(withApplicantRef(CAR.repeat(129))));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.fieldErrors()).extracting(ErrorResponse.FieldError::path).containsExactly("applicantRef");
    }

    private static ObjectNode withApplicantRef(String applicantRef) throws Exception {
        ObjectNode body = (ObjectNode) JSON.readTree(QuoteApiContractTest.CREATE_BODY);
        return body.put("applicantRef", applicantRef);
    }
}

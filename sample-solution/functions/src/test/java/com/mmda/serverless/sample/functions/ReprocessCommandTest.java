package com.mmda.serverless.sample.functions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReprocessCommandTest {

    @ParameterizedTest(name = "maxMessages={0} -> lote de {1}")
    @CsvSource(nullValues = "null", value = {
            "null, 10",
            "5, 5",
            "0, 1",
            "-3, 1",
            "10, 10",
            "50, 10"
    })
    void keepsTheBatchSizeWithinTheSqsRange(Integer maxMessages, int expected) {
        assertThat(new ReprocessCommand(maxMessages).batchSize()).isEqualTo(expected);
    }
}

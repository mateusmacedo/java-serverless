package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Desserialização dos eventos de SQS e de DynamoDB Streams no formato que a
 * Lambda recebe.
 */
class LambdaEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsAnSqsEventWithTheEventBridgeEnvelopeInTheBody() throws Exception {
        LambdaEvents.SqsEvent event = mapper.readValue(
                """
                        {"Records":[{"messageId":"m-1","receiptHandle":"r","body":"{\\"detail-type\\":\\"InsurerResultRecorded\\"}",
                          "attributes":{"ApproximateReceiveCount":"1"},"eventSource":"aws:sqs"}]}
                        """,
                LambdaEvents.SqsEvent.class);

        assertThat(event.records()).singleElement().satisfies(message -> {
            assertThat(message.messageId()).isEqualTo("m-1");
            assertThat(mapper.readTree(message.body()).get("detail-type").asText()).isEqualTo("InsurerResultRecorded");
        });
    }

    @Test
    void readsAStreamRecordAndConvertsTheImageToSdkAttributes() throws Exception {
        LambdaEvents.StreamEvent event = mapper.readValue("""
                {"Records":[{"eventID":"1","eventName":"INSERT","eventSource":"aws:dynamodb",
                  "dynamodb":{"Keys":{"pk":{"S":"OUTBOX#e"}},"SequenceNumber":"100","StreamViewType":"NEW_IMAGE",
                    "NewImage":{"pk":{"S":"OUTBOX#e"},"attempts":{"N":"0"},"flag":{"BOOL":true},"none":{"NULL":true},
                      "list":{"L":[{"S":"a"},{"N":"2"}]},"map":{"M":{"k":{"S":"v"}}},"set":{"SS":["x","y"]}}}}]}
                """, LambdaEvents.StreamEvent.class);

        LambdaEvents.StreamRecord record = event.records().getFirst();
        assertThat(record.eventName()).isEqualTo("INSERT");
        assertThat(record.dynamodb().sequenceNumber()).isEqualTo("100");
        Map<String, AttributeValue> item = LambdaEvents.toItem(record.dynamodb().newImage());
        assertThat(item.get("pk").s()).isEqualTo("OUTBOX#e");
        assertThat(item.get("attempts").n()).isEqualTo("0");
        assertThat(item.get("flag").bool()).isTrue();
        assertThat(item.get("none").nul()).isTrue();
        assertThat(item.get("list").l()).extracting(value -> value.s() != null ? value.s() : value.n())
                .containsExactly("a", "2");
        assertThat(item.get("map").m().get("k").s()).isEqualTo("v");
        assertThat(item.get("set").ss()).containsExactly("x", "y");
    }

    @Test
    void batchResponseUsesTheLambdaFieldNames() throws Exception {
        assertThat(mapper.writeValueAsString(LambdaEvents.BatchResponse.of(java.util.List.of("7"))))
                .isEqualTo("{\"batchItemFailures\":[{\"itemIdentifier\":\"7\"}]}");
    }
}

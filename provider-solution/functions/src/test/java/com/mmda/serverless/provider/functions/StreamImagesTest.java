package com.mmda.serverless.provider.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;

class StreamImagesTest {

    @Test
    void convertsEveryAttributeTypeOfTheStreamModel() {
        Map<String, AttributeValue> image = Map.of(
                "s", new AttributeValue().withS("text"),
                "n", new AttributeValue().withN("42"),
                "b", new AttributeValue().withB(ByteBuffer.wrap("raw".getBytes(StandardCharsets.UTF_8))),
                "bool", new AttributeValue().withBOOL(true),
                "null", new AttributeValue().withNULL(true),
                "ss", new AttributeValue().withSS("a", "b"),
                "ns", new AttributeValue().withNS("1", "2"),
                "m", new AttributeValue().withM(Map.of("inner", new AttributeValue().withS("x"))),
                "l", new AttributeValue().withL(new AttributeValue().withN("7")));

        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> converted = StreamImages
                .toSdk(image);

        assertThat(converted.get("s").s()).isEqualTo("text");
        assertThat(converted.get("n").n()).isEqualTo("42");
        assertThat(converted.get("b").b()).isEqualTo(SdkBytes.fromUtf8String("raw"));
        assertThat(converted.get("bool").bool()).isTrue();
        assertThat(converted.get("null").nul()).isTrue();
        assertThat(converted.get("ss").ss()).containsExactly("a", "b");
        assertThat(converted.get("ns").ns()).containsExactly("1", "2");
        assertThat(converted.get("m").m().get("inner").s()).isEqualTo("x");
        assertThat(converted.get("l").l()).extracting(value -> value.n()).isEqualTo(List.of("7"));
    }

    @Test
    void missingImageIsEmpty() {
        assertThat(StreamImages.toSdk(null)).isEmpty();
    }
}

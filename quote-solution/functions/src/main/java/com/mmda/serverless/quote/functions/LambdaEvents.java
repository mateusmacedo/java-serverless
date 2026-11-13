package com.mmda.serverless.quote.functions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Eventos de SQS e de DynamoDB Streams no formato que a Lambda recebe, e a
 * resposta de falha parcial.
 * WHY: os modelos do aws-lambda-java-events usam nomes de propriedade que o
 * Jackson do Quarkus não mapeia
 * sem mixins (por exemplo, {@code Records} e {@code NewImage}); records
 * próprios evitam a dependência.
 */
public final class LambdaEvents {

    private LambdaEvents() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SqsEvent(@JsonProperty("Records") List<SqsMessage> records) {

        public SqsEvent {
            records = records == null ? List.of() : records;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SqsMessage(String messageId, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamEvent(@JsonProperty("Records") List<StreamRecord> records) {

        public StreamEvent {
            records = records == null ? List.of() : records;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamRecord(String eventID, String eventName, StreamData dynamodb) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamData(
            @JsonProperty("NewImage") Map<String, JsonNode> newImage,
            @JsonProperty("SequenceNumber") String sequenceNumber) {
    }

    /**
     * Resposta com {@code ReportBatchItemFailures}: só os itens listados voltam.
     */
    public record BatchResponse(List<ItemFailure> batchItemFailures) {

        public static BatchResponse of(List<String> failedIdentifiers) {
            return new BatchResponse(failedIdentifiers.stream().map(ItemFailure::new).toList());
        }
    }

    public record ItemFailure(String itemIdentifier) {
    }

    /**
     * Converte a imagem do stream (JSON tipado do DynamoDB) para os atributos do
     * SDK v2.
     */
    public static Map<String, AttributeValue> toItem(Map<String, JsonNode> image) {
        Map<String, AttributeValue> item = new HashMap<>();
        image.forEach((name, value) -> item.put(name, toAttribute(value)));
        return item;
    }

    static AttributeValue toAttribute(JsonNode typed) {
        Iterator<Map.Entry<String, JsonNode>> fields = typed.properties().iterator();
        if (!fields.hasNext()) {
            throw new IllegalArgumentException("empty DynamoDB attribute");
        }
        Map.Entry<String, JsonNode> field = fields.next();
        JsonNode value = field.getValue();
        return switch (field.getKey()) {
            case "S" -> AttributeValue.fromS(value.asText());
            case "N" -> AttributeValue.fromN(value.asText());
            case "BOOL" -> AttributeValue.fromBool(value.asBoolean());
            case "NULL" -> AttributeValue.fromNul(true);
            case "B" -> AttributeValue.fromB(SdkBytes.fromByteArray(Base64.getDecoder().decode(value.asText())));
            case "SS" -> AttributeValue.fromSs(texts(value));
            case "NS" -> AttributeValue.fromNs(texts(value));
            case "BS" -> AttributeValue.fromBs(texts(value).stream()
                    .map(text -> SdkBytes.fromByteArray(Base64.getDecoder().decode(text))).toList());
            case "L" -> {
                List<AttributeValue> list = new ArrayList<>();
                value.forEach(element -> list.add(toAttribute(element)));
                yield AttributeValue.fromL(list);
            }
            case "M" -> {
                Map<String, AttributeValue> map = new HashMap<>();
                value.properties().forEach(entry -> map.put(entry.getKey(), toAttribute(entry.getValue())));
                yield AttributeValue.fromM(map);
            }
            default -> throw new IllegalArgumentException("unknown DynamoDB attribute type: " + field.getKey());
        };
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(element -> texts.add(element.asText()));
        return texts;
    }
}

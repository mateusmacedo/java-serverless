package com.mmda.serverless.provider.functions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Converte o {@code AttributeValue} do modelo de eventos da Lambda para o do SDK v2, ao ler o
 * {@code NewImage} do stream (desenho, §7).
 */
final class StreamImages {

    private StreamImages() {
    }

    static Map<String, AttributeValue> toSdk(
            Map<String, com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue> image) {
        Map<String, AttributeValue> converted = new LinkedHashMap<>();
        if (image != null) {
            image.forEach((name, value) -> converted.put(name, toSdkValue(value)));
        }
        return converted;
    }

    static AttributeValue toSdkValue(com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue value) {
        if (value.getS() != null) {
            return AttributeValue.fromS(value.getS());
        }
        if (value.getN() != null) {
            return AttributeValue.fromN(value.getN());
        }
        if (value.getB() != null) {
            return AttributeValue.fromB(SdkBytes.fromByteBuffer(value.getB()));
        }
        if (value.getBOOL() != null) {
            return AttributeValue.fromBool(value.getBOOL());
        }
        if (Boolean.TRUE.equals(value.getNULL())) {
            return AttributeValue.fromNul(true);
        }
        if (value.getSS() != null) {
            return AttributeValue.fromSs(value.getSS());
        }
        if (value.getNS() != null) {
            return AttributeValue.fromNs(value.getNS());
        }
        if (value.getBS() != null) {
            return AttributeValue.fromBs(value.getBS().stream().map(SdkBytes::fromByteBuffer).toList());
        }
        if (value.getM() != null) {
            return AttributeValue.fromM(toSdk(value.getM()));
        }
        if (value.getL() != null) {
            List<AttributeValue> list = value.getL().stream().map(StreamImages::toSdkValue).toList();
            return AttributeValue.fromL(list);
        }
        throw new IllegalArgumentException("Unsupported stream attribute value");
    }
}

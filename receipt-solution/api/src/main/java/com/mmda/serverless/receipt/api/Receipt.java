package com.mmda.serverless.receipt.api;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Comprovante como a API devolve. O item da tabela é gravado pelo passo {@code issue}, no módulo
 * {@code receipt-functions}; os nomes dos atributos precisam bater com os de lá.
 */
public record Receipt(String requestId, String signature, Long payloadBytes, String issuedAt) {

    static Receipt fromItem(Map<String, AttributeValue> item) {
        return new Receipt(
                item.get("requestId").s(),
                item.get("signature").s(),
                Long.valueOf(item.get("payloadBytes").n()),
                item.get("issuedAt").s());
    }
}

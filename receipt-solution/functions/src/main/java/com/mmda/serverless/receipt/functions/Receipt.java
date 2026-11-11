package com.mmda.serverless.receipt.functions;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Comprovante de um pedido concluído: saída do {@code issue}, entrada do {@code announce} e
 * {@code detail} do evento {@code ReceiptIssued}.
 *
 * @param issuedAt ISO-8601 em texto, para não depender da configuração de datas do mapper JSON
 */
public record Receipt(String requestId, String signature, Long payloadBytes, String issuedAt) {

    Map<String, AttributeValue> toItem() {
        return Map.of(
                "requestId", AttributeValue.fromS(requestId),
                "signature", AttributeValue.fromS(signature),
                "payloadBytes", AttributeValue.fromN(Long.toString(payloadBytes)),
                "issuedAt", AttributeValue.fromS(issuedAt));
    }

    static Receipt fromItem(Map<String, AttributeValue> item) {
        return new Receipt(
                item.get("requestId").s(),
                item.get("signature").s(),
                Long.valueOf(item.get("payloadBytes").n()),
                item.get("issuedAt").s());
    }
}

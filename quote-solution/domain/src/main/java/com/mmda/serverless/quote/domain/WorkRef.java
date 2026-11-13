package com.mmda.serverless.quote.domain;

import java.util.Objects;

/**
 * Referência opaca e determinística de um pedido à provider-solution (§3 do
 * desenho):
 * {@code wr_} + os 32 primeiros hexadecimais do SHA-256 de
 * {@code quoteId|version|kind|insurerId}. É a
 * única identidade que sai da quote; o task token fica no waiter.
 */
public record WorkRef(String value) {

    public enum Kind {
        ENRICHMENT,
        INSURER
    }

    public WorkRef {
        Objects.requireNonNull(value, "value");
    }

    public static WorkRef of(String quoteId, int version, Kind kind, String insurerId) {
        String target = kind == Kind.ENRICHMENT || insurerId == null ? "" : insurerId;
        String digest = Hashes.sha256Hex(quoteId + "|" + version + "|" + kind.name() + "|" + target);
        return new WorkRef("wr_" + digest.substring(0, 32));
    }

    /**
     * {@code eventId} do pedido: o mesmo pedido gravado de novo não cria um segundo
     * evento.
     */
    public String requestEventId() {
        return Hashes.uuidV5(value).toString();
    }

    @Override
    public String toString() {
        return value;
    }
}

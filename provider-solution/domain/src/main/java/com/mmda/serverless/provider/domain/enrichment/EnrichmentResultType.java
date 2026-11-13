package com.mmda.serverless.provider.domain.enrichment;

/** Resultado do enriquecimento. {@code NOT_FOUND} só quando declarado pelo provedor (RN07). */
public enum EnrichmentResultType {
    FOUND,
    NOT_FOUND,
    FAILED;

    /** {@code FAILED} nunca entra no índice de reuso (desenho, §8). */
    public boolean isReusable() {
        return this != FAILED;
    }
}

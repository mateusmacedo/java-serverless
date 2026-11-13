package com.mmda.serverless.quote.domain;

/**
 * Revisões monotônicas calculadas na leitura (§4 do desenho), como soma de
 * contadores que só crescem.
 * A revisão da versão e a da lista de ofertas são independentes e não se
 * comparam entre si.
 */
public final class Revisions {

    private Revisions() {
    }

    public static long versionRevision(long versionRevision, long resultRevisions, int offerCount, boolean selected) {
        return versionRevision + resultRevisions + offerCount + (selected ? 1 : 0);
    }

    public static long offersRevision(int offerCount, int terminalResults, boolean selected, boolean superseded) {
        return (long) offerCount + terminalResults + (selected ? 1 : 0) + (superseded ? 1 : 0);
    }
}

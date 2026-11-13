package com.mmda.serverless.provider.functions;

import java.util.Objects;

/**
 * Liderança que o worker detém sobre uma {@code queryKey}: a conclusão e o
 * fan-out só valem com esta
 * {@code fencingVersion} ainda no {@code INFLIGHT#} (desenho, §8, T15). O
 * fan-out responde aos seguidores das
 * versões {@code firstFencingVersion} a {@code fencingVersion}.
 */
record LeaderTerm(String queryKey, long fencingVersion, long firstFencingVersion) {

    LeaderTerm {
        Objects.requireNonNull(queryKey, "queryKey");
    }
}

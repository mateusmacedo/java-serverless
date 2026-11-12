package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.enrichment.EnrichmentResolved;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Política de reuso e liderança do enriquecimento (desenho, §12), dona da versão que vai na evidência, no
 * {@code REUSE#} e no {@code EnrichmentResolved}. A lease passa do timeout do worker (§9); o
 * {@code tombstoneSeconds} é o TTL da lápide do {@code INFLIGHT#} e dos {@code FOLLOWER#}.
 */
@ConfigurationProperties(prefix = "provider.reuse")
public record ReuseProperties(
        @DefaultValue("30") int leaseSeconds,
        @DefaultValue("3600") int tombstoneSeconds,
        @DefaultValue("reuse-v1") String policyVersion) {

    public ReuseProperties {
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (!EnrichmentResolved.POLICY_VERSION.matcher(policyVersion).matches()) {
            throw new IllegalArgumentException("The reuse policy version is outside the documented format: "
                    + policyVersion);
        }
    }

    Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    Duration tombstone() {
        return Duration.ofSeconds(tombstoneSeconds);
    }
}

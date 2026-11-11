package com.mmda.serverless.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vetores calculados à parte com hashlib, hmac e uuid do Python, para conferir a implementação. */
class IdentitiesTest {

    private static final String WORK_REF = "wr_c211b7c4e930ee39e2224cc9b7d8d93f";
    private static final String QUERY_ID = "q_132b742043a6d581a9a6370779501333";

    @Test
    void workRefIsTheHashOfQuoteVersionKindAndInsurer() {
        assertThat(Identities.workRef("quote-1", 1, Identities.INSURER, "alpha")).isEqualTo(WORK_REF);
        assertThat(Identities.workRef("quote-1", 1, Identities.ENRICHMENT, null))
                .isEqualTo("wr_1ff1565a199c3404652d0b90f29bde10");
    }

    @Test
    void queryIdIsDerivedFromTheWorkRef() {
        assertThat(Identities.queryId(WORK_REF)).isEqualTo(QUERY_ID).hasSize(34);
        assertThat(Identities.queryId(WORK_REF)).isEqualTo(Identities.queryId(WORK_REF));
        assertThat(Identities.queryId(WORK_REF + "x")).isNotEqualTo(QUERY_ID);
    }

    @Test
    void evidenceAndAttemptReferencesAreScopedByTheAttempt() {
        assertThat(Identities.evidenceId(QUERY_ID, 1)).isEqualTo("ev_e35e3074cb461e7ac85cf1c34cd80fbf");
        assertThat(Identities.evidenceId(QUERY_ID, 2)).isNotEqualTo(Identities.evidenceId(QUERY_ID, 1));
        assertThat(Identities.attemptRef(QUERY_ID, 3)).isEqualTo(QUERY_ID + "#0003");
    }

    @Test
    void uuidV5FollowsTheRfc() {
        UUID dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        assertThat(Digests.uuidV5(dnsNamespace, "www.example.com"))
                .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    void requestEventIdMatchesTheQuoteSolutionExample() {
        String workRef = Identities.workRef("5b0b6c1e-5f1d-4a8e-9a39-2f1f5d6e7c11", 1, Identities.ENRICHMENT, null);

        assertThat(workRef).isEqualTo("wr_109dd1cbf526511e21bb4e07bdffa510");
        assertThat(EventIds.request(workRef)).isEqualTo("3321dda3-d8fb-50ae-9412-ce1512112077");
    }

    @Test
    void eventIdsAreDeterministicPerFact() {
        assertThat(EventIds.NAMESPACE).isEqualTo(UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8"));
        assertThat(EventIds.insurerResultRecorded(QUERY_ID)).isEqualTo("2ce316c8-1455-55e9-be1c-696a3b6c2928");
        assertThat(EventIds.enrichmentResolved(QUERY_ID, WORK_REF)).isEqualTo("b9f43de4-5134-5d8e-8756-3cdb2fab89c8");
        assertThat(EventIds.request(WORK_REF)).isEqualTo("77c92e38-a927-5e67-bc1d-609c2ce348dd");
    }

    @Test
    void queryKeyIsAnHmacOfTheNormalizedParameters() {
        byte[] secret = "secret".getBytes(StandardCharsets.UTF_8);

        String key = QueryKeys.compute(secret, "org-acme", "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                " vehicle-1 ", "driver-1");

        assertThat(key).isEqualTo("366ac877170877156c6784a5067cea55a466d35ee128a624ec2d9bbb2eb19204");
        assertThat(QueryKeys.compute(secret, "org-globex", "enrichment-sim", "VEHICLE_DRIVER_PROFILE", "1",
                "vehicle-1", "driver-1")).isNotEqualTo(key);
        assertThat(QueryKeys.compute("other".getBytes(StandardCharsets.UTF_8), "org-acme", "enrichment-sim",
                "VEHICLE_DRIVER_PROFILE", "1", "vehicle-1", "driver-1")).isNotEqualTo(key);
    }

    @Test
    void queryKeyRequiresASecret() {
        assertThatThrownBy(() -> QueryKeys.compute(new byte[0], "org", "p", "o", "1", "v", "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

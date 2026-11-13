package com.mmda.serverless.quote.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Identificadores determinísticos, idempotência, revisões, prazos e permissões. */
class IdentifiersTest {

    @Test
    void workRefIsTheTruncatedSha256OfTheRequestIdentity() {
        assertThat(WorkRef.of("q1", 1, WorkRef.Kind.INSURER, "alpha").value())
                .isEqualTo("wr_2a10517c31612f97346d05f0c90b30fa");
        assertThat(WorkRef.of("q1", 1, WorkRef.Kind.ENRICHMENT, "ignored").value())
                .isEqualTo("wr_6146fd5bdcaf42eb2024cf359399ce31");
        assertThat(WorkRef.of("q1", 2, WorkRef.Kind.INSURER, "alpha"))
                .isNotEqualTo(WorkRef.of("q1", 1, WorkRef.Kind.INSURER, "alpha"));
    }

    @Test
    void deterministicEventIdsAreUuidV5InTheUrlNamespace() {
        assertThat(Hashes.uuidV5("wr_0123").toString()).isEqualTo("04e5e903-8ecb-53ed-9331-90fdc4440ff0");
        assertThat(QuoteEvents.completedEventId("q1", 1)).isEqualTo("71bf967f-f435-5918-aad4-1eb8ecd6b94c");
        WorkRef workRef = WorkRef.of("q1", 1, WorkRef.Kind.INSURER, "alpha");
        assertThat(workRef.requestEventId()).isEqualTo(Hashes.uuidV5(workRef.value()).toString());
    }

    @Test
    void t02_sameIntentWithAnotherFormattingIsAReplay() {
        String first = Idempotency.requestHash("{\"product\":\"AUTO\",\"risk\":{\"usage\":\"PERSONAL\"}}");
        String reformatted = Idempotency.requestHash("{ \"risk\" : { \"usage\" : \"PERSONAL\" }, \"product\" : \"AUTO\" }");

        assertThat(Idempotency.compare(first, reformatted)).isEqualTo(Idempotency.Decision.REPLAY);
    }

    @Test
    void t03_differentBodyWithTheSameKeyIsAConflict() {
        String first = Idempotency.requestHash("{\"product\":\"AUTO\",\"risk\":{\"usage\":\"PERSONAL\"}}");
        String changed = Idempotency.requestHash("{\"product\":\"AUTO\",\"risk\":{\"usage\":\"COMMERCIAL\"}}");

        assertThat(Idempotency.compare(first, changed)).isEqualTo(Idempotency.Decision.CONFLICT);
    }

    @Test
    void idempotencyKeyIsScopedByOrganizationOperationAndScope() {
        Idempotency.Key key = new Idempotency.Key("org-acme", Idempotency.Operation.CREATE_QUOTE, Idempotency.NO_SCOPE,
                "k-1");

        assertThat(key.pk()).isEqualTo("IDEMPOTENCY#org-acme#CREATE_QUOTE#-#k-1");
        assertThat(Idempotency.isValidKey("8b2f674a-770f-4982-a3d1-c0141eeed347")).isTrue();
        assertThat(Idempotency.isValidKey("with space")).isFalse();
        assertThat(Idempotency.isValidKey("")).isFalse();
        assertThat(Idempotency.isValidKey("x".repeat(201))).isFalse();
        assertThatThrownBy(() -> new Idempotency.Key("org", Idempotency.Operation.CREATE_QUOTE, "-", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tableKeysFollowTheDesign() {
        assertThat(QuoteKeys.versionSk(1)).isEqualTo("V#0001");
        assertThat(QuoteKeys.resultSk(12, "alpha")).isEqualTo("V#0012#RESULT#alpha");
        assertThat(QuoteKeys.executionName("abc", 3)).isEqualTo("q-abc-v3");
        assertThatThrownBy(() -> QuoteKeys.versionSk(0)).isInstanceOf(IllegalArgumentException.class);
        WorkRef workRef = WorkRef.of("q1", 1, WorkRef.Kind.INSURER, "alpha");
        assertThat(QuoteKeys.offerId(workRef)).startsWith("of_").hasSize(35).isEqualTo(QuoteKeys.offerId(workRef));
    }

    @Test
    void revisionsAreSumsOfMonotonicCounters() {
        assertThat(Revisions.versionRevision(3, 4, 2, true)).isEqualTo(10);
        assertThat(Revisions.offersRevision(2, 2, false, true)).isEqualTo(5);
    }

    @Test
    void requestDeadlineIsCappedByTheVersionDeadline() {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");

        assertThat(Deadlines.requestDeadline(now, Duration.ofSeconds(20), now.plusSeconds(300)))
                .isEqualTo(now.plusSeconds(20));
        assertThat(Deadlines.requestDeadline(now, Duration.ofSeconds(20), now.plusSeconds(5)))
                .isEqualTo(now.plusSeconds(5));
        assertThat(Deadlines.versionDeadline(now, Duration.ofSeconds(300))).isEqualTo(now.plusSeconds(300));
    }

    @Test
    void organizationAndPermissionsComeFromCognitoGroups() {
        Set<String> groups = Set.of("org_org-acme", "perm_quotes_read", "perm_offers_select", "other");

        assertThat(Permission.organizationFromGroups(groups)).contains("org-acme");
        assertThat(Permission.fromGroups(groups)).containsExactly(Permission.QUOTES_READ, Permission.OFFERS_SELECT);
        assertThat(Permission.organizationFromGroups(Set.of("org_a", "org_b"))).isEmpty();
        assertThat(Permission.organizationFromGroups(Set.of("org_"))).isEmpty();
    }

    @Test
    void retriesOnlyRetryableFailuresUpToTheLimit() {
        List<Duration> sleeps = new java.util.ArrayList<>();
        Retries retries = new Retries(3, Duration.ofMillis(10), Duration.ofMillis(40), sleeps::add);
        int[] calls = {0};

        assertThatThrownBy(() -> retries.run(() -> {
            calls[0]++;
            throw new IllegalStateException("conflict");
        }, failure -> true)).hasMessage("conflict");
        assertThat(calls[0]).isEqualTo(3);
        assertThat(sleeps).hasSize(2).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(Duration.ofMillis(40)));

        calls[0] = 0;
        assertThatThrownBy(() -> retries.run(() -> {
            calls[0]++;
            throw new IllegalArgumentException("permanent");
        }, failure -> failure instanceof IllegalStateException)).hasMessage("permanent");
        assertThat(calls[0]).isEqualTo(1);

        assertThat(retries.run(() -> "ok", failure -> true)).isEqualTo("ok");
    }
}

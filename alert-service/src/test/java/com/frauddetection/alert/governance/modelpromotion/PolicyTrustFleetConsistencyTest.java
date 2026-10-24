package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyTrustFleetConsistencyTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String DIGEST = "b".repeat(64);
    private final PolicyTrustFleetConsistency.Membership membership =
            PolicyTrustFleetConsistency.membership("rollout-7", "alert-a,alert-b", "publisher-a");

    @Test void oneOfThreeOldIsPartialAndCannotClaimCompleteRollout() {
        var result = assess(List.of(report("ALERT_VERIFIER", "alert-a", "epoch-a", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "epoch-b", 1, "a".repeat(64), NOW),
                report("PUBLISHER", "publisher-a", "epoch-p", 2, DIGEST, NOW)));
        assertThat(result.rolloutState()).isEqualTo("OBSERVED_PARTIAL");
        assertThat(result.mismatched()).contains(new PolicyTrustFleetConsistency.Member("ALERT_VERIFIER", "alert-b"));
    }

    @Test void publisherUpdatedWhileVerifierStaleAndInverseRemainPartial() {
        assertThat(assess(List.of(report("ALERT_VERIFIER", "alert-a", "a", 1, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 1, DIGEST, NOW),
                report("PUBLISHER", "publisher-a", "p", 2, DIGEST, NOW))).rolloutState())
                .isEqualTo("OBSERVED_PARTIAL");
        assertThat(assess(List.of(report("ALERT_VERIFIER", "alert-a", "a", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 2, DIGEST, NOW),
                report("PUBLISHER", "publisher-a", "p", 1, DIGEST, NOW))).rolloutState())
                .isEqualTo("OBSERVED_PARTIAL");
    }

    @Test void disappearedAndStaleInstancesRemainVisible() {
        var missing = assess(List.of(report("ALERT_VERIFIER", "alert-a", "a", 2, DIGEST, NOW),
                report("PUBLISHER", "publisher-a", "p", 2, DIGEST, NOW)));
        assertThat(missing.missing()).contains(new PolicyTrustFleetConsistency.Member("ALERT_VERIFIER", "alert-b"));
        var stale = assess(List.of(report("ALERT_VERIFIER", "alert-a", "a", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 2, DIGEST, NOW.minusSeconds(91)),
                report("PUBLISHER", "publisher-a", "p", 2, DIGEST, NOW)));
        assertThat(stale.stale()).contains(new PolicyTrustFleetConsistency.Member("ALERT_VERIFIER", "alert-b"));
        assertThat(stale.rolloutState()).isEqualTo("OBSERVED_PARTIAL");
    }

    @Test void duplicateIdentityOrFreshPreRestartEpochCannotSatisfyMembership() {
        var reports = List.of(report("ALERT_VERIFIER", "alert-a", "old-process", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-a", "new-process", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 2, DIGEST, NOW),
                report("PUBLISHER", "publisher-a", "p", 2, DIGEST, NOW));
        assertThat(assess(reports).rolloutState()).isEqualTo("DEPLOYMENT_FAILED");
        assertThat(assess(List.of(reports.get(0), reports.get(1), reports.get(2), reports.get(3)), true)
                .rolloutState()).isEqualTo("UNKNOWN");
        var afterOldExpires = List.of(report("ALERT_VERIFIER", "alert-a", "old-process", 2,
                        DIGEST, NOW.minusSeconds(91)), reports.get(1), reports.get(2), reports.get(3));
        assertThat(assess(afterOldExpires).rolloutState()).isEqualTo("OBSERVED_CONSISTENT");
    }

    @Test void sameLiveProcessEpochCannotRepresentTwoMembers() {
        var result = assess(List.of(report("ALERT_VERIFIER", "alert-a", "shared", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "shared", 2, DIGEST, NOW),
                report("PUBLISHER", "publisher-a", "publisher", 2, DIGEST, NOW)));
        assertThat(result.rolloutState()).isEqualTo("DEPLOYMENT_FAILED");
    }

    @Test void rollbackAndWrongDigestFailClosed() {
        var base = List.of(report("ALERT_VERIFIER", "alert-a", "a", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 2, DIGEST, NOW));
        assertThat(assess(List.of(base.get(0), base.get(1),
                report("PUBLISHER", "publisher-a", "p", 1, DIGEST, NOW)), 2)
                .rolloutState()).isEqualTo("REVOKED_OR_ROLLED_BACK");
        assertThat(assess(List.of(base.get(0), base.get(1),
                report("PUBLISHER", "publisher-a", "p", 2, "c".repeat(64), NOW)))
                .rolloutState()).isEqualTo("DEPLOYMENT_FAILED");
    }

    @Test void exactAllMemberObservationIsConsistentButNeverActivationAttestation() {
        var result = assess(List.of(report("ALERT_VERIFIER", "alert-a", "a", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 2, DIGEST, NOW),
                report("PUBLISHER", "publisher-a", "p", 2, DIGEST, NOW)));
        assertThat(result.rolloutState()).isEqualTo("OBSERVED_CONSISTENT");
        assertThat(result.missing()).isEmpty();
        assertThat(result.stale()).isEmpty();
        assertThat(result.mismatched()).isEmpty();
        assertThat(PolicyTrustFleetConsistency.membership(null, "alert-a", "publisher-a").complete()).isFalse();
    }

    @Test void freshUnexpectedInstanceFailsAndMissingPublisherStaysPartial() {
        var alerts = List.of(report("ALERT_VERIFIER", "alert-a", "a", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "alert-b", "b", 2, DIGEST, NOW));
        var missingPublisher = assess(alerts);
        assertThat(missingPublisher.rolloutState()).isNotEqualTo("OBSERVED_CONSISTENT");
        assertThat(missingPublisher.missing()).contains(
                new PolicyTrustFleetConsistency.Member("PUBLISHER", "publisher-a"));
        assertThat(assess(List.of(alerts.get(0), alerts.get(1),
                report("PUBLISHER", "publisher-a", "p", 2, DIGEST, NOW),
                report("ALERT_VERIFIER", "unlisted", "x", 2, DIGEST, NOW)))
                .rolloutState()).isEqualTo("DEPLOYMENT_FAILED");
    }

    private PolicyTrustFleetConsistency.Assessment assess(List<PolicyTrustInstanceObservation> observations) {
        return assess(observations, 1, false);
    }

    private PolicyTrustFleetConsistency.Assessment assess(List<PolicyTrustInstanceObservation> observations,
            int floor) {
        return assess(observations, floor, false);
    }

    private PolicyTrustFleetConsistency.Assessment assess(List<PolicyTrustInstanceObservation> observations,
            boolean truncated) {
        return assess(observations, 1, truncated);
    }

    private PolicyTrustFleetConsistency.Assessment assess(List<PolicyTrustInstanceObservation> observations,
            int floor, boolean truncated) {
        return PolicyTrustFleetConsistency.assess(membership, observations, 2, DIGEST, floor, NOW, truncated);
    }

    private PolicyTrustInstanceObservation report(String role, String id, String epoch, long generation,
            String digest, Instant observedAt) {
        return new PolicyTrustInstanceObservation(role + ":" + id + ":" + epoch, role, id,
                PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS, generation, digest, observedAt,
                "rollout-7", epoch, NOW.minusSeconds(300));
    }
}

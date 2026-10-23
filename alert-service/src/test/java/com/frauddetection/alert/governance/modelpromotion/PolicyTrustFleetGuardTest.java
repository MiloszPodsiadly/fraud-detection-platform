package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyTrustFleetGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String DIGEST = "c".repeat(64);

    @Test void allLoadedRegistriesMustMatchBothDeclaredRoles() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class)))
                .thenReturn(reports(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS, 2))
                .thenReturn(reports(PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS, 2))
                .thenReturn(reports(PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS, 2));
        assertThat(guard(mongo).consistent()).isTrue();
    }

    @Test void revokedKeyStillLoadedByPublisherStopsPublication() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class)))
                .thenReturn(reports(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS, 2))
                .thenReturn(reports(PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS, 2))
                .thenReturn(reports(PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS, 1));
        assertThat(guard(mongo).consistent()).isFalse();
    }

    @Test void unknownMembershipCannotAuthorizeNewPublication() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var guard = new PolicyTrustFleetGuard(mongo,
                PolicyTrustFleetConsistency.membership(null, "alert", "publisher"), loaded(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(guard.consistent()).isFalse();
    }

    private PolicyTrustFleetGuard guard(MongoTemplate mongo) {
        return new PolicyTrustFleetGuard(mongo,
                PolicyTrustFleetConsistency.membership("rollout", "alert", "publisher"), loaded(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Map<PolicyTrustRegistrySnapshotService.Registry, PolicyTrustFleetGuard.ExpectedRegistry> loaded() {
        var exact = new PolicyTrustFleetGuard.ExpectedRegistry(2, DIGEST, 2);
        return Map.of(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS, exact,
                PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS, exact,
                PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS, exact);
    }

    private List<PolicyTrustInstanceObservation> reports(
            PolicyTrustRegistrySnapshotService.Registry registry, long publisherGeneration) {
        return List.of(report(registry, "ALERT_VERIFIER", "alert", 2),
                report(registry, "PUBLISHER", "publisher", publisherGeneration));
    }

    private PolicyTrustInstanceObservation report(PolicyTrustRegistrySnapshotService.Registry registry,
            String role, String instanceId, long generation) {
        return new PolicyTrustInstanceObservation(role + ":" + instanceId + ":epoch:" + registry,
                role, instanceId, registry, generation, DIGEST, NOW, "rollout", "epoch",
                NOW.minusSeconds(60));
    }
}

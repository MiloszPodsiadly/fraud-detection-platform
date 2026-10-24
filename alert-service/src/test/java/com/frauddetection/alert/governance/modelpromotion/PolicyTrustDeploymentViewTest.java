package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyTrustDeploymentViewTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final PolicyTrustRegistrySnapshotService.Registry REGISTRY =
            PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS;
    private static final String DIGEST = "b".repeat(64);

    @Test
    void truncatedObservationSetCannotEstablishConsistentObservedGeneration() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        when(mongo.findById("request-1", PolicyTrustChangeDocument.class)).thenReturn(request("PENDING_OPERATOR_ACTION"));
        when(registries.snapshot(REGISTRY)).thenReturn(readyRegistry());
        List<PolicyTrustInstanceObservation> observations = IntStream.range(0, 101)
                .mapToObj(index -> observation("PUBLISHER", "publisher-" + index, NOW))
                .toList();
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class))).thenReturn(observations);
        var service = service(mongo, registries);

        var view = service.deployment(reader(), "request-1");

        assertThat(view.rolloutState()).isEqualTo("UNKNOWN");
        assertThat(view.mismatchReason()).isEqualTo("EXPECTED_MEMBERSHIP_UNAVAILABLE");
        assertThat(view.observationSetTruncated()).isTrue();
        assertThat(view.acknowledgements()).hasSize(100);
        assertThat(view.consistentObservedGeneration()).isNull();
        assertThat(view.consistentObservedRegistrySha256()).isNull();
        var query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(PolicyTrustInstanceObservation.class));
        assertThat(query.getValue().getLimit()).isEqualTo(101);
    }

    @Test
    void pendingReviewCannotBeReportedAsApprovedOrConsistentDeployment() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        when(mongo.findById("request-1", PolicyTrustChangeDocument.class)).thenReturn(request("PENDING_REVIEW"));
        when(registries.snapshot(REGISTRY)).thenReturn(readyRegistry());
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class))).thenReturn(List.of(
                observation("ALERT_VERIFIER", "alert-1", NOW), observation("PUBLISHER", "publisher-1", NOW)));
        var service = service(mongo, registries);

        var view = service.deployment(reader(), "request-1");

        assertThat(view.requestStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(view.candidateRegistrySha256()).isEqualTo(DIGEST);
        assertThat(view.rolloutState()).isEqualTo("PENDING_INDEPENDENT_REVIEW");
        assertThat(view.consistentObservedGeneration()).isNull();
        assertThat(view.consistentObservedRegistrySha256()).isNull();
    }

    @Test
    void matchingHeartbeatsWithoutDeclaredInventoryRemainUnknown() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        when(mongo.findById("request-1", PolicyTrustChangeDocument.class)).thenReturn(request("PENDING_OPERATOR_ACTION"));
        when(registries.snapshot(REGISTRY)).thenReturn(readyRegistry());
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class))).thenReturn(List.of(
                observation("ALERT_VERIFIER", "alert-1", NOW),
                observation("PUBLISHER", "publisher-1", NOW)));
        var view = service(mongo, registries).deployment(reader(), "request-1");
        assertThat(view.rolloutState()).isEqualTo("UNKNOWN");
        assertThat(view.membershipEvidence()).isEqualTo("UNKNOWN");
        assertThat(view.activationConfirmed()).isFalse();
    }

    @Test
    void changedFileWithoutVerifierReloadCannotClaimObservedConsistency() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        when(mongo.findById("request-1", PolicyTrustChangeDocument.class)).thenReturn(request("PENDING_OPERATOR_ACTION"));
        when(registries.snapshot(REGISTRY)).thenReturn(readyRegistry());
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class))).thenReturn(List.of(
                loaded("ALERT_VERIFIER", "alert-1", 1), loaded("PUBLISHER", "publisher-1", 1)));
        var service = new PolicyTrustChangeService(mongo,
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                mock(PolicyWebAuthnService.class), registries, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), PolicyTrustFleetConsistency.membership(
                        "rollout", "alert-1", "publisher-1"));
        var view = service.deployment(reader(), "request-1");
        assertThat(view.configuredFileGeneration()).isEqualTo(2L);
        assertThat(view.rolloutState()).isEqualTo("DEPLOYMENT_IN_PROGRESS");
        assertThat(view.consistentObservedGeneration()).isNull();
    }

    @Test
    void matchingLoadedFleetWithOldApiFileIsDeploymentFailure() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var registries = mock(PolicyTrustRegistrySnapshotService.class);
        when(mongo.findById("request-1", PolicyTrustChangeDocument.class))
                .thenReturn(request("PENDING_OPERATOR_ACTION"));
        when(registries.snapshot(REGISTRY)).thenReturn(new PolicyTrustRegistrySnapshotService.RegistryView(
                REGISTRY, "READY", 1L, "a".repeat(64), 1, List.of()));
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class))).thenReturn(List.of(
                loaded("ALERT_VERIFIER", "alert-1", 2), loaded("PUBLISHER", "publisher-1", 2)));
        var service = new PolicyTrustChangeService(mongo,
                mock(org.springframework.transaction.support.TransactionTemplate.class),
                mock(PolicyWebAuthnService.class), registries, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), PolicyTrustFleetConsistency.membership(
                        "rollout", "alert-1", "publisher-1"));

        var view = service.deployment(reader(), "request-1");

        assertThat(view.rolloutState()).isEqualTo("DEPLOYMENT_FAILED");
        assertThat(view.mismatchReason()).isEqualTo("API_REGISTRY_FILE_DIFFERS_FROM_APPROVED_CANDIDATE");
        assertThat(view.configuredFileGeneration()).isEqualTo(1L);
        assertThat(view.consistentObservedGeneration()).isNull();
        assertThat(view.activationConfirmed()).isFalse();
    }

    private PolicyTrustChangeService service(MongoTemplate mongo,
            PolicyTrustRegistrySnapshotService registries) {
        return new PolicyTrustChangeService(mongo, mock(org.springframework.transaction.support.TransactionTemplate.class),
                mock(PolicyWebAuthnService.class), registries, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PolicyTrustChangeDocument request(String status) {
        var intent = new PolicyTrustChangeService.Intent(REGISTRY, PolicyTrustChangeService.Action.GRANT,
                "oidc:" + "c".repeat(64), null, null, null, List.of("POLICY_APPROVER"),
                ModelGovernanceEnvironment.TEST, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                2, DIGEST, "case-1");
        return new PolicyTrustChangeDocument("request-1", intent, "a".repeat(64), 1,
                "b".repeat(64), "requester", "credential-1", "assertion-1", "trust-request:request-1", NOW,
                status, "reviewer", "credential-2", "assertion-2", "trust-review:review-1", NOW);
    }

    private PolicyTrustInstanceObservation observation(String role, String instanceId, Instant observedAt) {
        return new PolicyTrustInstanceObservation(role + ":" + instanceId, role, instanceId,
                REGISTRY, 2, DIGEST, observedAt);
    }

    private PolicyTrustInstanceObservation loaded(String role, String instanceId, long generation) {
        return new PolicyTrustInstanceObservation(role + ":" + instanceId + ":epoch", role, instanceId,
                REGISTRY, generation, DIGEST, NOW, "rollout", "epoch-" + instanceId, NOW.minusSeconds(20));
    }

    private PolicyTrustRegistrySnapshotService.RegistryView readyRegistry() {
        return new PolicyTrustRegistrySnapshotService.RegistryView(REGISTRY, "READY", 2L, DIGEST, 1, List.of());
    }

    private PolicyGovernanceActor reader() {
        return new PolicyGovernanceActor("reader", Set.of(ModelPolicyAuthority.TRUST_READ),
                "https://id.example", "reader");
    }
}

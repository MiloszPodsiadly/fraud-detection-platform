package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyTrustFleetGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String DIGEST = "c".repeat(64);
    @TempDir Path directory;
    private PolicyTrustObservationProof proof;
    private KeyPair alertKey;
    private KeyPair publisherKey;

    @BeforeEach void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("Ed25519");
        alertKey = generator.generateKeyPair();
        publisherKey = generator.generateKeyPair();
        var file = directory.resolve("reporters.json");
        Files.writeString(file, """
                {"version":"policy-trust-observation-keys-v1","deploymentId":"rollout","keys":[
                {"role":"ALERT_VERIFIER","instanceId":"alert","keyId":"alert-key","publicKeySpkiBase64":"%s"},
                {"role":"PUBLISHER","instanceId":"publisher","keyId":"publisher-key","publicKeySpkiBase64":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(alertKey.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(publisherKey.getPublic().getEncoded())));
        proof = PolicyTrustObservationProof.load(file);
    }

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
                Clock.fixed(NOW, ZoneOffset.UTC), proof);
        assertThat(guard.consistent()).isFalse();
    }

    @Test void forgedMongoDocumentCannotAuthorizeSignature() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var forged = report(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                "PUBLISHER", "publisher", 2);
        forged = new PolicyTrustInstanceObservation(forged.id(), forged.role(), forged.instanceId(),
                forged.registry(), forged.generation(), forged.digestSha256(), forged.observedAt(),
                forged.deploymentId(), forged.processEpoch(), forged.processStartedAt(),
                forged.proofKeyId(), "AAAA");
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class)))
                .thenReturn(List.of(report(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                        "ALERT_VERIFIER", "alert", 2), forged));
        assertThat(guard(mongo).consistent()).isFalse();
    }

    @Test void unavailableMongoAndAbsentSignerKeyFailClosed() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(PolicyTrustInstanceObservation.class)))
                .thenThrow(new SecurityException("observation read denied"));
        assertThat(guard(mongo).consistent()).isFalse();
        PolicyTrustRuntimeReporter.record(mongo, "ALERT_VERIFIER", "alert", "rollout",
                "epoch", NOW.minusSeconds(10), PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                2, DIGEST, Clock.fixed(NOW, ZoneOffset.UTC), proof, "alert-key", null);
        verify(mongo, never()).save(any(PolicyTrustInstanceObservation.class));
    }

    @Test void wrongRoleAndLegacyUnsignedReportCannotAuthorizeSignature() {
        var valid = report(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                "ALERT_VERIFIER", "alert", 2);
        var wrongRole = new PolicyTrustInstanceObservation(valid.id(), "PUBLISHER", valid.instanceId(),
                valid.registry(), valid.generation(), valid.digestSha256(), valid.observedAt(),
                valid.deploymentId(), valid.processEpoch(), valid.processStartedAt(),
                valid.proofKeyId(), valid.signatureBase64());
        assertThat(proof.verify(wrongRole)).isFalse();
        assertThat(proof.verify(new PolicyTrustInstanceObservation(valid.id(), valid.role(), valid.instanceId(),
                valid.registry(), valid.generation(), valid.digestSha256(), valid.observedAt(),
                valid.deploymentId(), valid.processEpoch(), valid.processStartedAt()))).isFalse();
        var tampered = new PolicyTrustInstanceObservation(valid.id(), valid.role(), valid.instanceId(),
                valid.registry(), 3, valid.digestSha256(), valid.observedAt(),
                valid.deploymentId(), valid.processEpoch(), valid.processStartedAt(),
                valid.proofKeyId(), valid.signatureBase64());
        assertThat(proof.verify(tampered)).isFalse();
        var publisher = report(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                "PUBLISHER", "publisher", 2);
        assertThatThrownBy(() -> proof.sign(publisher, "alert-key", alertKey.getPrivate()))
                .isInstanceOf(SecurityException.class);
    }

    private PolicyTrustFleetGuard guard(MongoTemplate mongo) {
        return new PolicyTrustFleetGuard(mongo,
                PolicyTrustFleetConsistency.membership("rollout", "alert", "publisher"), loaded(),
                Clock.fixed(NOW, ZoneOffset.UTC), proof);
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
        String epoch = "epoch-" + instanceId;
        var unsigned = new PolicyTrustInstanceObservation(role + ":" + instanceId + ":" + epoch + ":" + registry,
                role, instanceId, registry, generation, DIGEST, NOW, "rollout", epoch,
                NOW.minusSeconds(60));
        return proof.sign(unsigned, "ALERT_VERIFIER".equals(role) ? "alert-key" : "publisher-key",
                "ALERT_VERIFIER".equals(role) ? alertKey.getPrivate() : publisherKey.getPrivate());
    }
}

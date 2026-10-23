package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyTrustCandidateBuilderTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String FROM = "2025-01-01T00:00:00Z";
    private static final String UNTIL = "2028-01-01T00:00:00Z";
    private static final String CUTOFF = "2026-10-23T11:00:00Z";
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path tempDir;

    @Test
    void issuerAddRotateRetireAndRevokePreserveEveryOtherEntry() throws Exception {
        var old = key();
        var second = key();
        var newKey = key();
        var source = new ModelPromotionTrustedIssuerRegistry.Configuration(
                ModelPromotionTrustedIssuerRegistry.VERSION, 2, List.of(
                issuer("issuer-a", "old-key", old, ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE),
                issuer("issuer-b", "second-key", second, ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE)));
        var builder = issuerBuilder(source);
        var add = builder.build(issuerIntent(PolicyTrustChangeService.Action.ADD,
                "issuer-a", "new-key", newKey, null, null, null));
        var added = mapper.readValue(add.canonicalJson(), ModelPromotionTrustedIssuerRegistry.Configuration.class);
        assertThat(added.keys()).hasSize(3);
        assertThat(added.keys().get(1)).isEqualTo(source.keys().get(1));
        assertThat(add.sha256()).isEqualTo(GovernanceCanonicalJson.sha256(
                add.canonicalJson().getBytes(StandardCharsets.UTF_8)));
        assertThat(add.diff().proposedEntry().path("institutionalScopes").get(0).asText())
                .isEqualTo(ModelGovernanceScope.CURRENT_INSTITUTION);
        assertThat(add.diff().proposedEntry().path("publicKeySha256").asText())
                .isEqualTo(newKey.digest());
        assertThatThrownBy(() -> builder.build(issuerIntent(PolicyTrustChangeService.Action.REVOKE,
                "issuer-a", "old-key", old, null, null, add.sha256())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Claimed candidate digest");
        var rotate = builder.build(issuerIntent(PolicyTrustChangeService.Action.ROTATE,
                "issuer-a", "new-key", newKey, "old-key", CUTOFF, null));
        var rotated = mapper.readValue(rotate.canonicalJson(), ModelPromotionTrustedIssuerRegistry.Configuration.class);
        assertThat(rotated.keys().get(0).status())
                .isEqualTo(ModelPromotionTrustedIssuerRegistry.KeyStatus.RETIRED);
        assertThat(rotated.keys().get(0).validUntil()).isEqualTo(CUTOFF);
        assertThat(rotate.diff().updatedExistingEntry().path("status").asText())
                .isEqualTo("RETIRED");
        assertThat(rotated.keys().get(1)).isEqualTo(source.keys().get(1));
        assertThat(rotated.keys().get(2).keyId()).isEqualTo("new-key");
        assertThat(builder.build(issuerIntent(PolicyTrustChangeService.Action.RETIRE,
                "issuer-a", "old-key", old, null, CUTOFF, null)).sha256()).isNotEqualTo(add.sha256());
        var revoke = builder.build(issuerIntent(PolicyTrustChangeService.Action.REVOKE,
                "issuer-a", "old-key", old, null, null, null));
        var revoked = mapper.readValue(revoke.canonicalJson(), ModelPromotionTrustedIssuerRegistry.Configuration.class);
        assertThat(revoked.keys().get(0).status())
                .isEqualTo(ModelPromotionTrustedIssuerRegistry.KeyStatus.REVOKED);
        assertThat(revoked.keys().get(1)).isEqualTo(source.keys().get(1));
    }

    @Test
    void claimedDigestCannotSmuggleAnotherRoleOrRemoveUnrelatedKey() throws Exception {
        var old = key();
        var second = key();
        var newKey = key();
        var source = new InternalPolicyApprovalTrustRegistry.Configuration(
                InternalPolicyApprovalTrustRegistry.VERSION, 2, List.of(
                approval("operator-a", "old-key", old, InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR),
                approval("operator-b", "second-key", second,
                        InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER)));
        var builder = approvalBuilder(source);
        var legitimate = approvalIntent("new-key", newKey,
                List.of("POLICY_AUTHOR"), null);
        var candidate = builder.build(legitimate);
        var maliciousRole = new InternalPolicyApprovalTrustRegistry.Configuration(source.version(), 3,
                List.of(source.keys().get(0), source.keys().get(1),
                        approval("operator-a", "new-key", newKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_APPROVER)));
        var maliciousRemoval = new InternalPolicyApprovalTrustRegistry.Configuration(source.version(), 3,
                List.of(source.keys().get(0),
                        approval("operator-a", "new-key", newKey,
                                InternalPolicyApprovalTrustRegistry.Role.POLICY_AUTHOR)));
        assertThatThrownBy(() -> builder.build(approvalIntent("new-key", newKey,
                List.of("POLICY_AUTHOR"), GovernanceCanonicalJson.sha256(mapper, maliciousRole))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(approvalIntent("new-key", newKey,
                List.of("POLICY_AUTHOR"), GovernanceCanonicalJson.sha256(mapper, maliciousRemoval))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(candidate.sha256()).isNotEqualTo(GovernanceCanonicalJson.sha256(mapper, maliciousRole));
        var mismatchedAction = new PolicyTrustChangeService.Intent(
                PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS,
                PolicyTrustChangeService.Action.REVOKE, "operator-a", "old-key",
                old.digest(), null, List.of("POLICY_AUTHOR"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 3, candidate.sha256(),
                "case-trust-123");
        assertThatThrownBy(() -> builder.build(mismatchedAction))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(approvalIntent("old-key", old,
                List.of("POLICY_AUTHOR"), candidate.sha256())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidFingerprintDuplicateKeyAndStaleGenerationFailClosed() throws Exception {
        var old = key();
        var next = key();
        var source = new ModelPromotionTrustedIssuerRegistry.Configuration(
                ModelPromotionTrustedIssuerRegistry.VERSION, 2,
                List.of(issuer("issuer-a", "old-key", old,
                        ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE)));
        var builder = issuerBuilder(source);
        assertThatThrownBy(() -> builder.build(issuerIntent(PolicyTrustChangeService.Action.ADD,
                "issuer-a", "new-key", new Key(next.spki(), "0".repeat(64)), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(issuerIntent(PolicyTrustChangeService.Action.ADD,
                "issuer-a", "old-key", next, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        var stale = issuerIntent(PolicyTrustChangeService.Action.ADD,
                "issuer-a", "new-key", next, null, null, null);
        var wrongGeneration = new PolicyTrustChangeService.Intent(stale.registry(), stale.action(),
                stale.targetIdentity(), stale.keyId(), stale.publicKeySha256(), stale.publicKeySpkiBase64(),
                stale.roles(), stale.environment(), stale.intendedUse(), 4, null, stale.caseReference(),
                null, null, null, stale.validFrom(), stale.validUntil(), null, null);
        assertThatThrownBy(() -> builder.build(wrongGeneration)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlineActorGrantAndRevokeBindIssuerSubjectAndLeaveOtherMandatesUntouched() throws Exception {
        String issuer = "https://id.example";
        var existing = new OnlinePolicyActorTrustRegistry.Actor(
                PolicyGovernanceActor.identityId(issuer, "existing"), issuer, "existing",
                List.of(OnlinePolicyActorTrustRegistry.Role.POLICY_AUTHOR),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.TEST),
                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), FROM, UNTIL,
                OnlinePolicyActorTrustRegistry.Status.ACTIVE);
        var source = new OnlinePolicyActorTrustRegistry.Configuration(
                OnlinePolicyActorTrustRegistry.VERSION, 1, List.of(existing));
        Path path = tempDir.resolve("actors.json");
        Files.writeString(path, mapper.writeValueAsString(source));
        var builder = new PolicyTrustCandidateBuilder(new PolicyTrustRegistrySnapshotService(mapper,
                path.toString(), "", "", 1, 1, 1), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        String target = PolicyGovernanceActor.identityId(issuer, "new-operator");
        var grant = new PolicyTrustChangeService.Intent(PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                PolicyTrustChangeService.Action.GRANT, target, null, null, null,
                List.of("POLICY_APPROVER"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, null, "case-trust-123",
                issuer, "new-operator", "New operator", FROM, UNTIL, null, null);
        var candidate = builder.build(grant);
        var added = mapper.readValue(candidate.canonicalJson(), OnlinePolicyActorTrustRegistry.Configuration.class);
        assertThat(added.actors()).hasSize(2);
        assertThat(added.actors().get(0)).isEqualTo(existing);
        assertThat(added.actors().get(1).actorId()).isEqualTo(target);
        var escalatedExisting = new OnlinePolicyActorTrustRegistry.Actor(existing.actorId(),
                existing.issuer(), existing.subject(),
                List.of(OnlinePolicyActorTrustRegistry.Role.POLICY_APPROVER),
                existing.institutionalScopes(), existing.environments(), existing.intendedUses(),
                existing.validFrom(), existing.validUntil(), existing.status());
        var malicious = new OnlinePolicyActorTrustRegistry.Configuration(source.version(), 2,
                List.of(escalatedExisting, added.actors().get(1)));
        var claimedEscalation = new PolicyTrustChangeService.Intent(grant.registry(), grant.action(),
                grant.targetIdentity(), null, null, null, grant.roles(), grant.environment(),
                grant.intendedUse(), 2, GovernanceCanonicalJson.sha256(mapper, malicious),
                grant.caseReference(), issuer, grant.targetSubject(), grant.displayName(), FROM,
                UNTIL, null, null);
        assertThatThrownBy(() -> builder.build(claimedEscalation))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongIssuer = new PolicyTrustChangeService.Intent(grant.registry(), grant.action(), target,
                null, null, null, grant.roles(), grant.environment(), grant.intendedUse(),
                2, null, grant.caseReference(), "https://other.example", grant.targetSubject(),
                grant.displayName(), FROM, UNTIL, null, null);
        assertThatThrownBy(() -> builder.build(wrongIssuer)).isInstanceOf(IllegalArgumentException.class);
        var revoke = new PolicyTrustChangeService.Intent(grant.registry(),
                PolicyTrustChangeService.Action.REVOKE, existing.actorId(), null, null, null,
                List.of("POLICY_AUTHOR"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, null, "case-trust-123",
                issuer, existing.subject(), null, null, null, null, null);
        var revoked = mapper.readValue(builder.build(revoke).canonicalJson(),
                OnlinePolicyActorTrustRegistry.Configuration.class);
        assertThat(revoked.actors().get(0).status())
                .isEqualTo(OnlinePolicyActorTrustRegistry.Status.REVOKED);
    }

    private PolicyTrustCandidateBuilder issuerBuilder(ModelPromotionTrustedIssuerRegistry.Configuration source)
            throws Exception {
        Path path = tempDir.resolve("issuers.json");
        Files.writeString(path, mapper.writeValueAsString(source));
        return new PolicyTrustCandidateBuilder(new PolicyTrustRegistrySnapshotService(mapper,
                "", "", path.toString(), 1, 1, 1), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PolicyTrustCandidateBuilder approvalBuilder(InternalPolicyApprovalTrustRegistry.Configuration source)
            throws Exception {
        Path path = tempDir.resolve("approvals.json");
        Files.writeString(path, mapper.writeValueAsString(source));
        return new PolicyTrustCandidateBuilder(new PolicyTrustRegistrySnapshotService(mapper,
                "", path.toString(), "", 1, 1, 1), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PolicyTrustChangeService.Intent issuerIntent(PolicyTrustChangeService.Action action,
            String identity, String keyId, Key material, String replaces, String retireAt, String digest) {
        return new PolicyTrustChangeService.Intent(PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS,
                action, identity, keyId, material.digest(),
                action == PolicyTrustChangeService.Action.ADD || action == PolicyTrustChangeService.Action.ROTATE
                        ? material.spki() : null,
                List.of("POLICY_ISSUER"), ModelGovernanceEnvironment.PRODUCTION,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 3, digest, "case-trust-123",
                null, null, null, FROM, UNTIL, replaces, retireAt);
    }

    private PolicyTrustChangeService.Intent approvalIntent(String keyId, Key material,
            List<String> roles, String digest) {
        return new PolicyTrustChangeService.Intent(PolicyTrustRegistrySnapshotService.Registry.APPROVAL_KEYS,
                PolicyTrustChangeService.Action.ADD, "operator-a", keyId, material.digest(),
                material.spki(), roles, ModelGovernanceEnvironment.PRODUCTION,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 3, digest, "case-trust-123",
                null, null, null, FROM, UNTIL, null, null);
    }

    private ModelPromotionTrustedIssuerRegistry.ConfiguredKey issuer(String identity, String id, Key key,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status) {
        return new ModelPromotionTrustedIssuerRegistry.ConfiguredKey(identity, id, key.spki(), key.digest(),
                List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION),
                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), FROM, UNTIL, status);
    }

    private InternalPolicyApprovalTrustRegistry.ConfiguredKey approval(String identity, String id,
            Key key, InternalPolicyApprovalTrustRegistry.Role role) {
        return new InternalPolicyApprovalTrustRegistry.ConfiguredKey(identity, id, key.spki(), key.digest(),
                List.of(role), List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                List.of(ModelGovernanceEnvironment.PRODUCTION),
                List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING), FROM, UNTIL,
                ModelPromotionTrustedIssuerRegistry.KeyStatus.ACTIVE);
    }

    private Key key() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] spki = pair.getPublic().getEncoded();
        return new Key(Base64.getEncoder().encodeToString(spki), HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(spki)));
    }

    private record Key(String spki, String digest) { }
}

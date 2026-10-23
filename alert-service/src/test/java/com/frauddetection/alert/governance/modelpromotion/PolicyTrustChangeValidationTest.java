package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpolicy.PolicyWebAuthnService;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyTrustChangeValidationTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final PolicyTrustRegistrySnapshotService registries = mock(PolicyTrustRegistrySnapshotService.class);
    private final PolicyTrustChangeService service = new PolicyTrustChangeService(mongo,
            mock(TransactionTemplate.class), mock(PolicyWebAuthnService.class), registries,
            new ObjectMapper(), Clock.systemUTC());

    @Test
    void selfMandateAndProductionEscalationAreDeniedBeforeCeremony() {
        var operator = actor(Set.of(ModelPolicyAuthority.TRUST_REQUEST));
        assertThatThrownBy(() -> service.startRequest(operator, "self", actorIntent(operator.id(),
                ModelGovernanceEnvironment.TEST, 2)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.startRequest(operator, "production",
                actorIntent("oidc:" + "b".repeat(64), ModelGovernanceEnvironment.PRODUCTION, 2)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rollbackAndIncorrectKeyFingerprintFailBeforeWebAuthn() {
        var operator = actor(Set.of(ModelPolicyAuthority.TRUST_REQUEST));
        assertThatThrownBy(() -> service.startRequest(operator, "rollback",
                actorIntent("oidc:" + "b".repeat(64), ModelGovernanceEnvironment.TEST, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        when(registries.requireReady(PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS))
                .thenReturn(new PolicyTrustRegistrySnapshotService.RegistryView(
                        PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS, "READY", 1L,
                        "a".repeat(64), 1, List.of()));
        var badKey = new PolicyTrustChangeService.Intent(
                PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS,
                PolicyTrustChangeService.Action.ADD, "issuer", "new-key", "c".repeat(64),
                "not-a-public-key", List.of("POLICY_ISSUER"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, "b".repeat(64), "case-1");
        assertThatThrownBy(() -> service.startRequest(operator, "fingerprint", badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");

        var oversizedKey = new PolicyTrustChangeService.Intent(
                PolicyTrustRegistrySnapshotService.Registry.ISSUER_KEYS,
                PolicyTrustChangeService.Action.ADD, "issuer", "new-key", "c".repeat(64),
                "A".repeat(257), List.of("POLICY_ISSUER"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, "b".repeat(64), "case-1");
        assertThatThrownBy(() -> service.startRequest(operator, "oversized-key", oversizedKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    void duplicateRolesAreRejectedBeforeRegistryLookup() {
        var operator = actor(Set.of(ModelPolicyAuthority.TRUST_REQUEST));
        var duplicateRoles = new PolicyTrustChangeService.Intent(
                PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                PolicyTrustChangeService.Action.GRANT, "oidc:" + "b".repeat(64), null, null, null,
                List.of("POLICY_APPROVER", "POLICY_APPROVER"), ModelGovernanceEnvironment.TEST,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING, 2, "b".repeat(64), "case-1");
        assertThatThrownBy(() -> service.startRequest(operator, "duplicate-roles", duplicateRoles))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Structured trust change request is invalid");
    }

    @Test
    void nonProductionDiffCanBeReviewedWhenUnrelatedRegistryEntriesAreProduction() {
        var intent = actorIntent("oidc:" + "b".repeat(64), ModelGovernanceEnvironment.TEST, 2);
        String canonicalJson = "{\"actors\":[{\"environments\":[\"PRODUCTION\"]}],\"generation\":2,\"version\":1}\n";
        String digest = GovernanceCanonicalJson.sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        var request = new PolicyTrustChangeDocument("nonprod", intent, "a".repeat(64),
                1, "b".repeat(64), digest, false, "maker", "credential", "c".repeat(64),
                "proof", java.time.Instant.EPOCH, "PENDING_REVIEW", null, null, null, null, null);
        var candidate = new PolicyTrustCandidateDocument("nonprod", intent.registry(),
                intent.action().name(), 1, "b".repeat(64), 2, canonicalJson, digest,
                "{}\n", "Nonproduction actor change", false);
        when(mongo.findById("nonprod", PolicyTrustChangeDocument.class)).thenReturn(request);
        when(mongo.findById("nonprod", PolicyTrustCandidateDocument.class)).thenReturn(candidate);
        assertThat(service.candidate(actor(Set.of(ModelPolicyAuthority.TRUST_READ)), "nonprod")
                .candidateSha256()).isEqualTo(digest);
        when(mongo.findById("nonprod", PolicyTrustChangeDocument.class))
                .thenReturn(new PolicyTrustChangeDocument("nonprod", intent, "a".repeat(64),
                        1, "b".repeat(64), digest, true, "maker", "credential", "c".repeat(64),
                        "proof", java.time.Instant.EPOCH, "PENDING_REVIEW", null, null, null, null, null));
        when(mongo.findById("nonprod", PolicyTrustCandidateDocument.class))
                .thenReturn(new PolicyTrustCandidateDocument("nonprod", intent.registry(),
                        intent.action().name(), 1, "b".repeat(64), 2, canonicalJson, digest,
                        "{}\n", "Production actor change", true));
        assertThatThrownBy(() -> service.candidate(
                actor(Set.of(ModelPolicyAuthority.TRUST_READ)), "nonprod"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void productionIntentCannotBeReadUsingOnlyTheCandidateFlag() {
        var intent = actorIntent("oidc:" + "b".repeat(64), ModelGovernanceEnvironment.PRODUCTION, 2);
        String canonicalJson = "{\"actors\":[],\"generation\":2,\"version\":1}\n";
        String digest = GovernanceCanonicalJson.sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        var request = new PolicyTrustChangeDocument("production-intent", intent, "a".repeat(64),
                1, "b".repeat(64), digest, false, "maker", "credential", "c".repeat(64),
                "proof", java.time.Instant.EPOCH, "PENDING_REVIEW", null, null, null, null, null);
        var candidate = new PolicyTrustCandidateDocument("production-intent", intent.registry(),
                intent.action().name(), 1, "b".repeat(64), 2, canonicalJson, digest,
                "{}\n", "Production actor change", false);
        when(mongo.findById("production-intent", PolicyTrustChangeDocument.class)).thenReturn(request);
        when(mongo.findById("production-intent", PolicyTrustCandidateDocument.class)).thenReturn(candidate);

        assertThatThrownBy(() -> service.candidate(actor(Set.of(ModelPolicyAuthority.TRUST_READ)),
                "production-intent")).isInstanceOf(AccessDeniedException.class);
    }

    private PolicyTrustChangeService.Intent actorIntent(String target,
            ModelGovernanceEnvironment environment, long generation) {
        return new PolicyTrustChangeService.Intent(
                PolicyTrustRegistrySnapshotService.Registry.ONLINE_ACTORS,
                PolicyTrustChangeService.Action.GRANT, target, null, null, null,
                List.of("POLICY_APPROVER"), environment, ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                generation, "b".repeat(64), "case-1");
    }

    private PolicyGovernanceActor actor(Set<String> authorities) {
        String issuer = "https://id.example";
        String rawSubject = "security-operator";
        return new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, rawSubject),
                authorities, issuer, rawSubject);
    }
}

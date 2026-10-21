package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlinePolicyApproverDirectoryTest {
    @TempDir Path temp;

    @Test
    void optionalDisplayNameDoesNotChangeLegacyActorRegistryDigest() {
        var actor = actor("legacy-approver", Set.of());
        var config = new OnlinePolicyActorTrustRegistry.Configuration(
                OnlinePolicyActorTrustRegistry.VERSION, 1,
                List.of(new OnlinePolicyActorTrustRegistry.Actor(actor.id(), actor.issuer(),
                        actor.subject(), List.of(OnlinePolicyActorTrustRegistry.Role.POLICY_APPROVER),
                        List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                        List.of(ModelGovernanceEnvironment.TEST),
                        List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                        "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                        OnlinePolicyActorTrustRegistry.Status.ACTIVE)));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.writeValueAsString(config)).doesNotContain("displayName");
        assertThat(OnlinePolicyActorTrustRegistry.fromConfiguration(config, 1).canonicalSha256())
                .isEqualTo(com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson.sha256(mapper, config));
    }

    @Test
    void onlyMandatedScopedApproversAreVisibleAndGenerationRollbackFailsClosed() throws Exception {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        PolicyGovernanceActor caller = actor("author", Set.of(ModelPolicyAuthority.DIRECTORY_READ));
        PolicyGovernanceActor candidate = actor("approver", Set.of());
        var config = new OnlinePolicyActorTrustRegistry.Configuration(
                OnlinePolicyActorTrustRegistry.VERSION, 4,
                List.of(new OnlinePolicyActorTrustRegistry.Actor(candidate.id(), candidate.issuer(),
                        candidate.subject(), List.of(OnlinePolicyActorTrustRegistry.Role.POLICY_APPROVER),
                        List.of(ModelGovernanceScope.CURRENT_INSTITUTION),
                        List.of(ModelGovernanceEnvironment.TEST),
                        List.of(ModelPromotionIntendedUse.FRAUD_RISK_SCORING),
                        now.minusSeconds(60).toString(), now.plusSeconds(60).toString(),
                        OnlinePolicyActorTrustRegistry.Status.ACTIVE, "Independent Reviewer")));
        Path path = temp.resolve("actors.json");
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(path, mapper.writeValueAsString(config));
        var directory = new OnlinePolicyApproverDirectory(mapper, path.toString(), 4,
                Clock.fixed(now, ZoneOffset.UTC));
        var scope = new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                ModelGovernanceEnvironment.TEST);
        assertThat(directory.eligible(caller, scope, ModelPromotionIntendedUse.FRAUD_RISK_SCORING))
                .extracting(OnlinePolicyApproverDirectory.Entry::displayName)
                .containsExactly("Independent Reviewer");
        assertThat(directory.eligible(candidateWithDirectory(candidate), scope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING)).isEmpty();
        assertThatThrownBy(() -> directory.eligible(actor("reader", Set.of()), scope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING))
                .isInstanceOf(AccessDeniedException.class);
        var rollback = new OnlinePolicyApproverDirectory(mapper, path.toString(), 5,
                Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> rollback.eligible(caller, scope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING)).isInstanceOf(RuntimeException.class);
        var expired = new OnlinePolicyApproverDirectory(mapper, path.toString(), 4,
                Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.requireEligible(candidate, scope,
                ModelPromotionIntendedUse.FRAUD_RISK_SCORING)).isInstanceOf(SecurityException.class);
    }

    private PolicyGovernanceActor actor(String subject, Set<String> authorities) {
        String issuer = "https://identity.example";
        return new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, subject),
                authorities, issuer, subject);
    }

    private PolicyGovernanceActor candidateWithDirectory(PolicyGovernanceActor candidate) {
        return new PolicyGovernanceActor(candidate.id(), Set.of(ModelPolicyAuthority.DIRECTORY_READ),
                candidate.issuer(), candidate.subject());
    }
}

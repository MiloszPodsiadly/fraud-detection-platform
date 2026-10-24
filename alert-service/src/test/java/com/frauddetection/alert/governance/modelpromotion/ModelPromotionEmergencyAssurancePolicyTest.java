package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionEmergencyAssurancePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private final ModelPromotionEmergencyAssurancePolicy policy =
            new ModelPromotionEmergencyAssurancePolicy("https://trusted.example",
                    "urn:verified:mfa", 300);

    @Test void acceptsOnlyTrustedFreshSignedSessionEvidence() {
        assertThatCode(() -> policy.require(actor("https://trusted.example", "urn:verified:mfa",
                NOW.minusSeconds(60)), NOW)).doesNotThrowAnyException();
        for (var candidate : new PolicyGovernanceActor[] {
                actor("https://other.example", "urn:verified:mfa", NOW),
                actor("https://trusted.example", "urn:untrusted", NOW),
                actor("https://trusted.example", null, NOW),
                actor("https://trusted.example", "urn:verified:mfa", NOW.minusSeconds(301)),
                actor("https://trusted.example", "urn:verified:mfa", NOW.plusSeconds(31)),
                new PolicyGovernanceActor("oidc:test", Set.of(), "https://trusted.example", "subject") }) {
            assertThatThrownBy(() -> policy.require(candidate, NOW))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> new ModelPromotionEmergencyAssurancePolicy("", "", 300)
                .require(actor("https://trusted.example", "urn:verified:mfa", NOW), NOW))
                .isInstanceOf(AccessDeniedException.class);
    }

    private PolicyGovernanceActor actor(String issuer, String acr, Instant authenticatedAt) {
        return new PolicyGovernanceActor(PolicyGovernanceActor.identityId(issuer, "subject"), Set.of(),
                issuer, "subject", new PolicyGovernanceActor.AuthenticationEvidence(acr, authenticatedAt));
    }
}

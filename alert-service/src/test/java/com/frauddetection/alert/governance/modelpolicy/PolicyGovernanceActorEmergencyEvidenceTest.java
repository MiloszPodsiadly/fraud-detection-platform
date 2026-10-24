package com.frauddetection.alert.governance.modelpolicy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyGovernanceActorEmergencyEvidenceTest {
    @Test void derivesAssuranceOnlyFromTheAuthenticatedOidcIdToken() {
        Instant authenticatedAt = Instant.parse("2026-10-24T10:00:00Z");
        var assured = actor(Map.of("acr", "urn:verified:mfa", "auth_time", authenticatedAt.getEpochSecond()));
        assertThat(assured.authenticationEvidence().acr()).isEqualTo("urn:verified:mfa");
        assertThat(assured.authenticationEvidence().authenticatedAt()).isEqualTo(authenticatedAt);
        var amrOnly = actor(Map.of("amr", List.of("mfa"), "auth_time", authenticatedAt.getEpochSecond()));
        assertThat(amrOnly.authenticationEvidence().acr()).isNull();
    }

    private PolicyGovernanceActor actor(Map<String, Object> extra) {
        var claims = new java.util.HashMap<String, Object>(extra);
        claims.put("iss", "https://trusted.example");
        claims.put("sub", "emergency-operator");
        var now = Instant.parse("2026-10-24T10:00:00Z");
        var token = new OidcIdToken("signed-id-token", now, now.plusSeconds(300), claims);
        var user = new DefaultOidcUser(List.of(), token);
        return PolicyGovernanceActor.fromOidcSession(
                new OAuth2AuthenticationToken(user, List.of(), "keycloak"));
    }
}

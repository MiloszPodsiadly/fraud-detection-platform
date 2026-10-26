package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAnalystAuthenticationConverterTest {

    private final JwtSecurityProperties properties = new JwtSecurityProperties(
            true,
            false,
            "https://issuer.example.test",
            "https://issuer.example.test/.well-known/jwks.json",
            "sub",
            "groups",
            Map.of(
                    "fraud-readonly-analyst", AnalystRole.READ_ONLY_ANALYST,
                    "fraud-analyst", AnalystRole.ANALYST,
                    "fraud-reviewer", AnalystRole.REVIEWER,
                    "fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN
            ),
            Set.of(),
            "fraud-detection-platform-api",
            false
    );

    private final JwtAnalystAuthenticationConverter converter = new JwtAnalystAuthenticationConverter(
            properties,
            new AnalystAuthenticationFactory()
    );

    @Test
    void shouldBuildAnalystPrincipalFromJwtGroups() {
        Jwt jwt = jwt(Map.of(
                "sub", "analyst-42",
                "groups", List.of("fraud-analyst", "fraud-reviewer", "other-group")
        ));

        var authentication = converter.convert(jwt);

        assertThat(authentication.getPrincipal()).isInstanceOf(AnalystPrincipal.class);
        AnalystPrincipal principal = (AnalystPrincipal) authentication.getPrincipal();
        assertThat(principal.userId()).isEqualTo("analyst-42");
        assertThat(principal.roles()).containsExactlyInAnyOrder(AnalystRole.ANALYST, AnalystRole.REVIEWER);
        assertThat(principal.authorities()).contains(
                AnalystAuthority.ALERT_READ,
                AnalystAuthority.ALERT_DECISION_SUBMIT,
                AnalystAuthority.FRAUD_CASE_UPDATE
        );
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .contains("ROLE_ANALYST", "ROLE_REVIEWER", AnalystAuthority.FRAUD_CASE_UPDATE);
    }

    @Test
    void shouldSupportDelimitedStringAccessClaim() {
        Jwt jwt = jwt(Map.of(
                "sub", "admin-1",
                "groups", "fraud-ops-admin fraud-analyst"
        ));

        AnalystPrincipal principal = (AnalystPrincipal) converter.convert(jwt).getPrincipal();

        assertThat(principal.roles()).contains(AnalystRole.FRAUD_OPS_ADMIN, AnalystRole.ANALYST);
        assertThat(principal.authorities()).containsExactlyInAnyOrderElementsOf(AnalystAuthority.ALL);
    }

    @Test
    void shouldKeepAuthenticatedPrincipalWithoutMappedRolesWhenJwtContainsNoKnownGroups() {
        Jwt jwt = jwt(Map.of(
                "sub", "analyst-7",
                "groups", List.of("unknown-group")
        ));

        AnalystPrincipal principal = (AnalystPrincipal) converter.convert(jwt).getPrincipal();

        assertThat(principal.userId()).isEqualTo("analyst-7");
        assertThat(principal.roles()).isEmpty();
        assertThat(principal.authorities()).isEmpty();
    }

    @Test
    void shouldRejectJwtWhenConfiguredUserIdClaimIsMissing() {
        Jwt jwt = jwt(Map.of("groups", List.of("fraud-analyst")));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("JWT user id claim is required.");
    }

    @Test
    void shouldRejectJwtWhenAccessClaimTypeIsUnsupported() {
        Jwt jwt = jwt(Map.of(
                "sub", "analyst-5",
                "groups", Map.of("name", "fraud-analyst")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("JWT access claim has unsupported type.");
    }

    @Test
    void shouldKeepSubAsCanonicalUserIdEvenWhenPreferredUsernameIsPresent() {
        Jwt jwt = jwt(Map.of(
                "sub", "subject-123",
                "preferred_username", "reviewer.local",
                "groups", List.of("fraud-reviewer")
        ));

        AnalystPrincipal principal = (AnalystPrincipal) converter.convert(jwt).getPrincipal();

        assertThat(principal.userId()).isEqualTo("subject-123");
        assertThat(principal.roles()).containsExactly(AnalystRole.REVIEWER);
    }

    @Test
    void modelPromotionIdentityUsesTrustedIssuerAndSubjectWhenDisplayClaimDiffers() {
        JwtSecurityProperties displayProperties = new JwtSecurityProperties(
                true, false, "https://issuer.example.test", "https://issuer.example.test/.well-known/jwks.json",
                "preferred_username", "groups", Map.of("fraud-reviewer", AnalystRole.REVIEWER), Set.of(),
                "fraud-detection-platform-api", false);
        JwtAnalystAuthenticationConverter displayConverter = new JwtAnalystAuthenticationConverter(
                displayProperties, new AnalystAuthenticationFactory());
        AnalystPrincipal principal = (AnalystPrincipal) displayConverter.convert(jwt(Map.of(
                "sub", "immutable-subject", "preferred_username", "reviewer.display",
                "groups", List.of("fraud-reviewer")))).getPrincipal();

        assertThat(principal.userId()).isEqualTo("reviewer.display");
        assertThat(principal.institutionalActorId())
                .isEqualTo("https://issuer.example.test#immutable-subject");
        assertThat(principal.readAuditActorId()).isEqualTo(com.frauddetection.alert.governance.modelpolicy
                .PolicyGovernanceActor.identityId("https://issuer.example.test", "immutable-subject"));
    }

    @Test
    void shouldGrantProductionPromotionScopeOnlyForConfiguredTrustedAccessValue() {
        JwtSecurityProperties productionProperties = new JwtSecurityProperties(
                true,
                false,
                "https://issuer.example.test",
                "https://issuer.example.test/.well-known/jwks.json",
                "sub",
                "groups",
                Map.of("fraud-analyst", AnalystRole.ANALYST),
                Set.of("bank-production-model-promotion"),
                "fraud-detection-platform-api",
                false
        );
        JwtAnalystAuthenticationConverter productionConverter = new JwtAnalystAuthenticationConverter(
                productionProperties, new AnalystAuthenticationFactory()
        );

        AnalystPrincipal ordinary = (AnalystPrincipal) productionConverter.convert(jwt(Map.of(
                "sub", "analyst-1", "groups", List.of("fraud-analyst")
        ))).getPrincipal();
        AnalystPrincipal explicitlyGranted = (AnalystPrincipal) productionConverter.convert(jwt(Map.of(
                "sub", "analyst-2", "groups", List.of("fraud-analyst", "bank-production-model-promotion")
        ))).getPrincipal();

        assertThat(ordinary.authorities()).doesNotContain(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION);
        assertThat(explicitlyGranted.authorities()).contains(AnalystAuthority.MODEL_PROMOTION_SCOPE_PRODUCTION);
    }

    @Test
    void modelPolicyMandateRequiresExplicitGroupAndValidatedIssuerSubject() {
        JwtSecurityProperties governed = new JwtSecurityProperties(true, false,
                "https://issuer.example.test/realm", "https://issuer.example.test/jwks", "sub", "groups",
                Map.of("fraud-reviewer", AnalystRole.REVIEWER), Set.of());
        JwtAnalystAuthenticationConverter governedConverter = new JwtAnalystAuthenticationConverter(
                governed, new AnalystAuthenticationFactory(), new ModelPolicyIamMapping(Map.of(
                        "policy-approver", Set.of(ModelPolicyAuthority.APPROVE))));
        AnalystPrincipal reviewerOnly = (AnalystPrincipal) governedConverter.convert(jwt(Map.of(
                "iss", "https://issuer.example.test/realm", "sub", "reviewer-1",
                "groups", List.of("fraud-reviewer")))).getPrincipal();
        AnalystPrincipal granted = (AnalystPrincipal) governedConverter.convert(jwt(Map.of(
                "iss", "https://issuer.example.test/realm", "sub", "reviewer-1",
                "groups", List.of("fraud-reviewer", "policy-approver")))).getPrincipal();
        AnalystPrincipal wrongIssuer = (AnalystPrincipal) governedConverter.convert(jwt(Map.of(
                "iss", "https://other.example.test/realm", "sub", "reviewer-1",
                "groups", List.of("policy-approver")))).getPrincipal();
        assertThat(reviewerOnly.authorities()).doesNotContain(ModelPolicyAuthority.APPROVE);
        assertThat(granted.authorities()).contains(ModelPolicyAuthority.APPROVE);
        assertThat(granted.institutionalActorId()).isEqualTo("https://issuer.example.test/realm#reviewer-1");
        assertThat(wrongIssuer.institutionalActorId()).isNull();
    }

    private Jwt jwt(Map<String, Object> claims) {
        java.util.Map<String, Object> trustedClaims = new java.util.LinkedHashMap<>();
        trustedClaims.put("iss", "https://issuer.example.test");
        trustedClaims.put("aud", List.of("fraud-detection-platform-api"));
        trustedClaims.putAll(claims);
        return new Jwt(
                "token-value",
                Instant.parse("2026-04-23T10:00:00Z"),
                Instant.parse("2026-04-23T11:00:00Z"),
                Map.of("alg", "none"),
                trustedClaims
        );
    }
}

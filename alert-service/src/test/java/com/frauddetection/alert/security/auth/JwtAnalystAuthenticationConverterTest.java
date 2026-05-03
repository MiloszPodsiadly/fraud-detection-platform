package com.frauddetection.alert.security.auth;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAnalystAuthenticationConverterTest {

    private final JwtSecurityProperties properties = new JwtSecurityProperties(
            true,
            false,
            null,
            "https://issuer.example.test/.well-known/jwks.json",
            "sub",
            "groups",
            Map.of(
                    "fraud-readonly-analyst", AnalystRole.READ_ONLY_ANALYST,
                    "fraud-analyst", AnalystRole.ANALYST,
                    "fraud-reviewer", AnalystRole.REVIEWER,
                    "fraud-ops-admin", AnalystRole.FRAUD_OPS_ADMIN
            )
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

    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                Instant.parse("2026-04-23T10:00:00Z"),
                Instant.parse("2026-04-23T11:00:00Z"),
                Map.of("alg", "none"),
                claims
        );
    }
}

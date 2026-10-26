package com.frauddetection.alert.security.principal;

import com.frauddetection.alert.security.authorization.AnalystAuthority;
import com.frauddetection.alert.security.authorization.AnalystRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentAnalystUserTest {

    private final CurrentAnalystUser currentAnalystUser = new CurrentAnalystUser();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnEmptyWhenAuthenticationIsMissing() {
        assertThat(currentAnalystUser.get()).isEmpty();
    }

    @Test
    void shouldReturnAnalystPrincipalFromSecurityContext() {
        var principal = new AnalystPrincipal(
                "analyst-1",
                Set.of(AnalystRole.ANALYST),
                AnalystRole.ANALYST.authorities()
        );
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(AnalystAuthority.ALERT_READ))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(currentAnalystUser.get())
                .contains(principal);
    }

    @Test
    void shouldPreserveValidatedIssuerAndSubjectForReadAuditIdentity() {
        java.time.Instant issuedAt = java.time.Instant.parse("2026-01-01T00:00:00Z");
        OidcIdToken token = new OidcIdToken("token", issuedAt, issuedAt.plusSeconds(300),
                java.util.Map.of("iss", "https://issuer.example", "sub", "same-subject"));
        DefaultOidcUser oidcUser = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_ANALYST")), token);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                oidcUser, "credentials", List.of(new SimpleGrantedAuthority("ROLE_ANALYST"))));

        assertThat(currentAnalystUser.get())
                .get()
                .extracting(AnalystPrincipal::institutionalActorId)
                .isEqualTo("https://issuer.example#same-subject");
    }
}

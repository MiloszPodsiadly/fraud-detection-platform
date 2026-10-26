package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPromotionRateLimitActorKeyResolverTest {

    @Test
    void demoKeysAreStableNamespacedAndDoNotCreateAnInstitutionalActor() {
        ModelPromotionRateLimitActorKeyResolver resolver = resolver(true, false, "test");
        Authentication first = authenticated(new AnalystPrincipal("demo-analyst-a", Set.of(), Set.of()));
        Authentication second = authenticated(new AnalystPrincipal("demo-analyst-b", Set.of(), Set.of()));

        assertThat(resolver.resolve(first)).startsWith("demo:").doesNotContain("demo-analyst-a");
        assertThat(resolver.resolve(first)).isEqualTo(resolver.resolve(first));
        assertThat(resolver.resolve(first)).isNotEqualTo(resolver.resolve(second));
        assertThatThrownBy(() -> PolicyGovernanceActor.from(first)).isInstanceOf(AccessDeniedException.class);
        assertThat(first.getAuthorities()).noneMatch(authority -> authority.getAuthority()
                .equals("model-promotion:scope:production"));
    }

    @Test
    void oidcQuotaIdentityUsesIssuerAndSubjectAndSeparatesIdenticalSubjectsAcrossIssuers() {
        ModelPromotionRateLimitActorKeyResolver resolver = resolver(false, true, "prod");
        String firstIssuer = resolver.resolve(oidc("https://issuer-a.example", "same-subject"));
        String secondIssuer = resolver.resolve(oidc("https://issuer-b.example", "same-subject"));

        assertThat(firstIssuer).isEqualTo(resolver.resolve(oidc("https://issuer-a.example", "same-subject")));
        assertThat(firstIssuer).startsWith("oidc:").isNotEqualTo(secondIssuer);
    }

    @Test
    void jwtPrincipalUsesItsTrustedInstitutionalActorIdentity() {
        ModelPromotionRateLimitActorKeyResolver resolver = resolver(false, true, "prod");
        AnalystPrincipal principal = new AnalystPrincipal("display-name", Set.of(), Set.of(),
                "https://issuer.example#subject-1");
        Authentication authentication = authenticated(principal);

        assertThat(resolver.resolve(authentication))
                .isEqualTo(PolicyGovernanceActor.identityId("https://issuer.example", "subject-1"));
    }

    @Test
    void demoFallbackIsUnavailableOutsideExplicitDemoProfilesOrWhenJwtModeIsEnabled() {
        Authentication demo = authenticated(new AnalystPrincipal("demo-a", Set.of(), Set.of()));

        assertThatThrownBy(() -> resolver(true, false, "prod").resolve(demo))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolver(true, true, "test").resolve(demo))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolver(false, false, "test").resolve(demo))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingOrUnauthenticatedIdentityIsRejected() {
        ModelPromotionRateLimitActorKeyResolver resolver = resolver(true, false, "test");
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolver.resolve(new UsernamePasswordAuthenticationToken(
                new AnalystPrincipal("demo", Set.of(), Set.of()), null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resolver.resolve(authenticated("untrusted-principal")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private ModelPromotionRateLimitActorKeyResolver resolver(boolean demo, boolean jwt, String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new ModelPromotionRateLimitActorKeyResolver(demo, jwt, environment);
    }

    private Authentication oidc(String issuer, String subject) {
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("test-token", now, now.plusSeconds(300), Map.of(
                "iss", issuer, "sub", subject));
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ANALYST")), token);
        return new UsernamePasswordAuthenticationToken(user, "test", user.getAuthorities());
    }

    private Authentication authenticated(Object principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }
}

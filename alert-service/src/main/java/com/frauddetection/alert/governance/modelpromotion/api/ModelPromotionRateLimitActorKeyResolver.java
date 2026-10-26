package com.frauddetection.alert.governance.modelpromotion.api;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;

/** Resolves quota identity without changing the institutional identity used by governance authorization. */
public class ModelPromotionRateLimitActorKeyResolver {

    private final boolean demoAuthEnabled;
    private final boolean jwtAuthEnabled;
    private final Environment environment;

    public ModelPromotionRateLimitActorKeyResolver(boolean demoAuthEnabled, boolean jwtAuthEnabled,
            Environment environment) {
        this.demoAuthEnabled = demoAuthEnabled;
        this.jwtAuthEnabled = jwtAuthEnabled;
        this.environment = environment;
    }

    public String resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated model-promotion actor required");
        }
        if (authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser
                || authentication.getPrincipal() instanceof AnalystPrincipal analyst
                    && StringUtils.hasText(analyst.institutionalActorId())) {
            return PolicyGovernanceActor.from(authentication).id();
        }
        if (authentication.getPrincipal() instanceof AnalystPrincipal analyst
                && demoAuthEnabled && !jwtAuthEnabled
                && environment.matchesProfiles("local", "dev", "docker-local", "test")
                && StringUtils.hasText(analyst.userId())) {
            String encodedSubject = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    analyst.userId().trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "demo:" + PolicyGovernanceActor.identityId("demo-auth", encodedSubject)
                    .substring("oidc:".length());
        }
        throw new AccessDeniedException("Trusted issuer and subject are required for model-promotion rate limiting");
    }
}

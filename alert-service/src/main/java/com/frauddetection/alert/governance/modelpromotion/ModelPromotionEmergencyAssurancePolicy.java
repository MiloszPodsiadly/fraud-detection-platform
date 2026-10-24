package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Admission only for a configured issuer whose signed ACR has a verified high-assurance meaning. */
@Component
public final class ModelPromotionEmergencyAssurancePolicy {
    private final String trustedIssuer;
    private final String trustedAcr;
    private final long maxAgeSeconds;

    public ModelPromotionEmergencyAssurancePolicy(
            @Value("${app.security.model-promotion-emergency.trusted-issuer:}") String trustedIssuer,
            @Value("${app.security.model-promotion-emergency.trusted-acr:}") String trustedAcr,
            @Value("${app.security.model-promotion-emergency.max-age-seconds:300}") long maxAgeSeconds) {
        this.trustedIssuer = trustedIssuer;
        this.trustedAcr = trustedAcr;
        if (maxAgeSeconds < 1 || maxAgeSeconds > 300)
            throw new IllegalArgumentException("Emergency authentication freshness must be 1..300 seconds");
        this.maxAgeSeconds = maxAgeSeconds;
    }

    void require(PolicyGovernanceActor actor, Instant now) {
        var evidence = actor == null ? null : actor.authenticationEvidence();
        if (trustedIssuer.isBlank() || trustedAcr.isBlank() || actor == null
                || !trustedIssuer.equals(actor.issuer()) || evidence == null
                || !trustedAcr.equals(evidence.acr()) || evidence.authenticatedAt() == null
                || evidence.authenticatedAt().isAfter(now.plusSeconds(30))
                || evidence.authenticatedAt().isBefore(now.minusSeconds(maxAgeSeconds)))
            throw new AccessDeniedException("Fresh verified emergency OIDC assurance is unavailable");
    }
}

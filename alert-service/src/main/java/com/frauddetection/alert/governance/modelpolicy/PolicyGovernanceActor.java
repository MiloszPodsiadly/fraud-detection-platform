package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.security.principal.AnalystPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/** Stable issuer+subject identity from an authenticated OIDC/JWT principal only. */
public record PolicyGovernanceActor(String id, Set<String> authorities, String issuer, String subject) {
    public PolicyGovernanceActor(String id, Set<String> authorities) {
        this(id, authorities, null, null);
    }

    public static PolicyGovernanceActor from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated OIDC actor required");
        }
        String issuer = null;
        String subject = null;
        if (authentication.getPrincipal() instanceof OidcUser user && user.getIdToken() != null
                && user.getIdToken().getIssuer() != null && user.getIdToken().getSubject() != null) {
            issuer = user.getIdToken().getIssuer().toString();
            subject = user.getIdToken().getSubject();
        } else if (authentication.getPrincipal() instanceof AnalystPrincipal analyst) {
            String identity = analyst.institutionalActorId();
            int separator = identity == null ? -1 : identity.lastIndexOf('#');
            if (separator > 0) {
                issuer = identity.substring(0, separator);
                subject = identity.substring(separator + 1);
            }
        }
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AccessDeniedException("Stable issuer and subject are required for policy governance");
        }
        return new PolicyGovernanceActor("oidc:" + sha256(issuer + "#" + subject),
                authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                        .collect(Collectors.toUnmodifiableSet()), issuer, subject);
    }

    public static PolicyGovernanceActor fromOidcSession(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser)) {
            throw new AccessDeniedException("OIDC login session required for policy WebAuthn actions");
        }
        return from(authentication);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

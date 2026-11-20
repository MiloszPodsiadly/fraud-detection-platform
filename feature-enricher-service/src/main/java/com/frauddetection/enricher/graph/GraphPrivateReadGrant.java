package com.frauddetection.enricher.graph;

import java.time.Instant;
import java.util.Objects;

/** Opaque Graph diagnostic grant reference. No production issuer is registered in this phase. */
public final class GraphPrivateReadGrant {
    static final String PURPOSE = "GRAPH_PRIVATE_DIAGNOSTIC_READ";
    static final String AUDIENCE = "graph-diagnostics-private";

    private final String principal;
    private final String purpose;
    private final String audience;
    private final GraphIdentityScope scope;
    private final int policyVersion;
    private final Instant expiresAt;
    private final boolean testOnly;

    private GraphPrivateReadGrant(String principal, GraphIdentityScope scope, int policyVersion,
                                  Instant expiresAt, boolean testOnly) {
        if (principal == null || principal.isBlank() || principal.length() > 128
                || principal.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("GRAPH_READ_GRANT_INVALID");
        }
        this.principal = principal;
        this.scope = Objects.requireNonNull(scope, "GRAPH_SCOPE_REQUIRED");
        GraphValidation.version(policyVersion);
        GraphValidation.millisecondInstant(expiresAt);
        if (!testOnly) { throw new IllegalArgumentException("GRAPH_READ_GRANT_INVALID"); }
        this.policyVersion = policyVersion;
        this.expiresAt = expiresAt;
        this.purpose = PURPOSE;
        this.audience = AUDIENCE;
        this.testOnly = true;
    }

    static GraphPrivateReadGrant testOnly(String principal, GraphIdentityScope scope, int policyVersion,
                                          Instant expiresAt) {
        return new GraphPrivateReadGrant(principal, scope, policyVersion, expiresAt, true);
    }

    boolean matches(String requestingPrincipal, GraphIdentityScope requestedScope, Instant now) {
        return testOnly && PURPOSE.equals(purpose) && AUDIENCE.equals(audience)
                && principal.equals(requestingPrincipal) && scope.equals(requestedScope)
                && expiresAt.isAfter(now);
    }

    int policyVersion() { return policyVersion; }
    Instant expiresAt() { return expiresAt; }

    @Override public String toString() { return "GraphPrivateReadGrant[redacted]"; }
}

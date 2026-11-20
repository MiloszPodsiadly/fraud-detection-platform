package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.SourceScope;

import java.time.Instant;
import java.util.Objects;

/** Independent Graph reader authorization; never derived from the Device diagnostic read grant. */
public final class DeviceGraphReadGrant {
    static final String PURPOSE = "GRAPH_RELATION_PRIVATE_READ";
    static final String AUDIENCE = "graph-relations-private";

    private final String principal;
    private final String purpose;
    private final String audience;
    private final DeviceAssertions.Scope sourceScope;
    private final String sourceAudience;
    private final int pseudonymKeyVersion;
    private final Instant expiresAt;
    private final boolean testOnly;

    private DeviceGraphReadGrant(String principal, DeviceAssertions.Scope sourceScope, String sourceAudience,
                                 int pseudonymKeyVersion, Instant expiresAt, boolean testOnly) {
        DeviceAssertions.reference(principal);
        this.sourceScope = Objects.requireNonNull(sourceScope, "DEVICE_GRAPH_SCOPE_REQUIRED");
        DeviceAssertions.reference(sourceAudience);
        if (pseudonymKeyVersion < 1 || pseudonymKeyVersion > 65_535 || expiresAt == null
                || expiresAt.getNano() % 1_000_000 != 0 || !testOnly) {
            throw new IllegalArgumentException("DEVICE_GRAPH_READ_GRANT_INVALID");
        }
        this.principal = principal;
        this.purpose = PURPOSE;
        this.audience = AUDIENCE;
        this.sourceAudience = sourceAudience;
        this.pseudonymKeyVersion = pseudonymKeyVersion;
        this.expiresAt = expiresAt;
        this.testOnly = true;
    }

    static DeviceGraphReadGrant testOnly(String principal, DeviceAssertions.Scope sourceScope, String sourceAudience,
                                         int pseudonymKeyVersion, Instant expiresAt) {
        return new DeviceGraphReadGrant(principal, sourceScope, sourceAudience, pseudonymKeyVersion, expiresAt, true);
    }

    boolean currentFor(SourceScope source, int keyVersion, Instant now, String requestingPrincipal) {
        return testOnly && PURPOSE.equals(purpose) && AUDIENCE.equals(audience)
                && source != null && now != null && expiresAt.isAfter(now)
                && principal.equals(requestingPrincipal)
                && keyVersion == pseudonymKeyVersion && sourceAudience.equals(source.audience())
                && DeviceAssertionVerifier.TRANSACTION_PURPOSE.equals(source.purpose())
                && sourceScope.matches(source.issuer(), source.institution(), source.source(), source.sourceEpoch());
    }

    int pseudonymKeyVersion() { return pseudonymKeyVersion; }
    Instant expiresAt() { return expiresAt; }

    @Override public String toString() { return "DeviceGraphReadGrant[test-only,redacted]"; }
}

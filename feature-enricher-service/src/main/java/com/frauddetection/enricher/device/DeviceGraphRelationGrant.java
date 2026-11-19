package com.frauddetection.enricher.device;

import com.frauddetection.common.events.source.SourceScope;

import java.time.Instant;
import java.util.Objects;

/** Server-owned, exact-scope test grant. There is deliberately no production grant factory. */
public final class DeviceGraphRelationGrant {
    static final String PURPOSE = "GRAPH_RELATION_PROJECTION";
    static final String AUDIENCE = "feature-enricher-device-graph-private";

    private final String grantId;
    private final String purpose;
    private final String audience;
    private final DeviceAssertions.Scope sourceScope;
    private final String sourceAudience;
    private final int pseudonymKeyVersion;
    private final Instant expiresAt;
    private final boolean testOnly;

    private DeviceGraphRelationGrant(String grantId, DeviceAssertions.Scope sourceScope, String sourceAudience,
                                     int pseudonymKeyVersion, Instant expiresAt, boolean testOnly) {
        DeviceAssertions.token(grantId);
        this.sourceScope = Objects.requireNonNull(sourceScope, "DEVICE_GRAPH_SCOPE_REQUIRED");
        DeviceAssertions.reference(sourceAudience);
        if (pseudonymKeyVersion < 1 || pseudonymKeyVersion > 65_535 || expiresAt == null
                || expiresAt.getNano() % 1_000_000 != 0 || !testOnly) {
            throw new IllegalArgumentException("DEVICE_GRAPH_RELATION_GRANT_INVALID");
        }
        this.grantId = grantId;
        this.purpose = PURPOSE;
        this.audience = AUDIENCE;
        this.sourceAudience = sourceAudience;
        this.pseudonymKeyVersion = pseudonymKeyVersion;
        this.expiresAt = expiresAt;
        this.testOnly = true;
    }

    static DeviceGraphRelationGrant testOnly(String grantId, DeviceAssertions.Scope sourceScope, String sourceAudience,
                                             int pseudonymKeyVersion, Instant expiresAt) {
        return new DeviceGraphRelationGrant(grantId, sourceScope, sourceAudience, pseudonymKeyVersion, expiresAt, true);
    }

    boolean currentFor(SourceScope source, int keyVersion, Instant now) {
        return testOnly && PURPOSE.equals(purpose) && AUDIENCE.equals(audience)
                && source != null && now != null && expiresAt.isAfter(now)
                && keyVersion == pseudonymKeyVersion
                && DeviceAssertionVerifier.TRANSACTION_PURPOSE.equals(source.purpose())
                && sourceAudience.equals(source.audience())
                && sourceScope.matches(source.issuer(), source.institution(), source.source(), source.sourceEpoch());
    }

    String grantId() { return grantId; }
    int pseudonymKeyVersion() { return pseudonymKeyVersion; }
    Instant expiresAt() { return expiresAt; }

    @Override public String toString() { return "DeviceGraphRelationGrant[test-only,redacted]"; }
}

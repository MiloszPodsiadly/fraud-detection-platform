package com.frauddetection.enricher.persistence;

import com.frauddetection.common.events.source.SourceLineage;
import com.frauddetection.common.events.source.SourceScope;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Redis partition selected from verified private source authority or explicit legacy-only mode. */
public final class FeatureNamespace {
    private static final FeatureNamespace LEGACY = new FeatureNamespace(true, "legacy-v1");
    private static final byte[] DOMAIN = "fraud-feature-namespace/v1".getBytes(StandardCharsets.US_ASCII);

    private final boolean legacy;
    private final String keySegment;

    private FeatureNamespace(boolean legacy, String keySegment) {
        this.legacy = legacy;
        this.keySegment = Objects.requireNonNull(keySegment);
    }

    public static FeatureNamespace legacy() { return LEGACY; }

    public static FeatureNamespace attested(SourceLineage authority) {
        if (authority == null || authority.state() != SourceLineage.State.VERIFIED || authority.source() == null) {
            throw new IllegalArgumentException("FEATURE_SOURCE_AUTHORITY_REQUIRED");
        }
        return new FeatureNamespace(false, digest(authority.source()));
    }

    public boolean isLegacy() { return legacy; }
    String keySegment() { return keySegment; }

    private static String digest(SourceScope source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            for (String value : new String[] {source.issuer(), source.institution(), source.source(), source.sourceEpoch()}) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                digest.update(encoded);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("FEATURE_NAMESPACE_HASH_UNAVAILABLE");
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof FeatureNamespace namespace
                && legacy == namespace.legacy && keySegment.equals(namespace.keySegment);
    }

    @Override public int hashCode() { return Objects.hash(legacy, keySegment); }
    @Override public String toString() { return legacy ? "FeatureNamespace[LEGACY]" : "FeatureNamespace[ATTESTED]"; }
}

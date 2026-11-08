package com.frauddetection.common.events.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.List;

/** Independent server configuration; no trust anchors are supplied by default. */
@ConfigurationProperties("fraud.source-verification")
public record SourceVerificationProperties(List<TrustedKey> keys) {
    public SourceVerificationProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
        if (keys.size() > 64) {
            throw new IllegalArgumentException("SOURCE_TRUST_POLICY_CAPACITY_EXCEEDED");
        }
    }

    public record TrustedKey(String issuer, String institution, String source, String sourceEpoch,
                             String signingKeyVersion, String algorithm, String publicKeyBase64,
                             Instant notBefore, Instant notAfter, boolean revoked,
                             String purpose, String audience, String policyVersion) {
        public TrustedKey {
            for (String value : new String[] {issuer, institution, source, sourceEpoch, signingKeyVersion,
                    purpose, audience, policyVersion}) {
                SourceScope.requireReference(value);
            }
            if (!SourceSignedInput.ALGORITHM.equals(algorithm)
                    || publicKeyBase64 == null || publicKeyBase64.length() > 256
                    || notBefore == null || notAfter == null || !notBefore.isBefore(notAfter)) {
                throw new IllegalArgumentException("SOURCE_TRUST_POLICY_INVALID");
            }
        }
        @Override public String toString() { return "TrustedSourceKey[redacted]"; }
    }

    @Override public String toString() { return "SourceVerificationProperties[redacted]"; }
}

package com.frauddetection.alert.audit.trust;

public record AuditTrustAttestationSignature(
        String signature,
        String keyId,
        String algorithm
) {
}

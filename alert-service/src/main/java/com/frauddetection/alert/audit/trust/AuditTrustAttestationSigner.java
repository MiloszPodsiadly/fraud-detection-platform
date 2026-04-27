package com.frauddetection.alert.audit.trust;

import java.util.Optional;

public interface AuditTrustAttestationSigner {

    String mode();

    default String signatureStrength() {
        return signingEnabled() ? "PRODUCTION_READY" : "NONE";
    }

    default String keyId() {
        return null;
    }

    boolean signingEnabled();

    Optional<AuditTrustAttestationSignature> sign(byte[] canonicalPayload);

    boolean verify(byte[] canonicalPayload, String signature, String keyId);
}

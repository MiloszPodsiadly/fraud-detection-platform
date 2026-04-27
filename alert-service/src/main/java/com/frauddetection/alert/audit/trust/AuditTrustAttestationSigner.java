package com.frauddetection.alert.audit.trust;

import java.util.Optional;

public interface AuditTrustAttestationSigner {

    String mode();

    String signatureStrength();

    default String keyId() {
        return null;
    }

    boolean signingEnabled();

    Optional<AuditTrustAttestationSignature> sign(byte[] canonicalPayload);

    boolean verify(byte[] canonicalPayload, String signature, String keyId);
}

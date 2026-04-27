package com.frauddetection.alert.audit.trust;

import java.util.Optional;

public interface AuditTrustAttestationSigner {

    String mode();

    boolean signingEnabled();

    Optional<AuditTrustAttestationSignature> sign(byte[] canonicalPayload);

    boolean verify(byte[] canonicalPayload, String signature, String keyId);
}

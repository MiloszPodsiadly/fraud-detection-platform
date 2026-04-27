package com.frauddetection.alert.audit.trust;

import java.util.Optional;

public class DisabledAuditTrustAttestationSigner implements AuditTrustAttestationSigner {

    @Override
    public String mode() {
        return "disabled";
    }

    @Override
    public boolean signingEnabled() {
        return false;
    }

    @Override
    public String signatureStrength() {
        return "NONE";
    }

    @Override
    public Optional<AuditTrustAttestationSignature> sign(byte[] canonicalPayload) {
        return Optional.empty();
    }

    @Override
    public boolean verify(byte[] canonicalPayload, String signature, String keyId) {
        return false;
    }
}

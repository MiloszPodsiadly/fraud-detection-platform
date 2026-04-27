package com.frauddetection.alert.audit.trust;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

public class LocalDevAuditTrustAttestationSigner implements AuditTrustAttestationSigner {

    public static final String ALGORITHM = "HMAC-SHA256-LOCAL-DEV";
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final String keyId;
    private final byte[] secret;

    public LocalDevAuditTrustAttestationSigner(String keyId, byte[] secret) {
        this.keyId = keyId;
        this.secret = secret.clone();
    }

    @Override
    public String mode() {
        return "local-dev";
    }

    @Override
    public boolean signingEnabled() {
        return true;
    }

    @Override
    public Optional<AuditTrustAttestationSignature> sign(byte[] canonicalPayload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonicalPayload));
            return Optional.of(new AuditTrustAttestationSignature(signature, keyId, ALGORITHM));
        } catch (Exception exception) {
            throw new AuditTrustAttestationException("Audit trust attestation signing failed.", exception);
        }
    }

    @Override
    public boolean verify(byte[] canonicalPayload, String signature, String keyId) {
        if (signature == null || signature.isBlank() || keyId == null || keyId.isBlank() || !this.keyId.equals(keyId)) {
            return false;
        }
        String expected = sign(canonicalPayload)
                .map(AuditTrustAttestationSignature::signature)
                .orElseThrow(() -> new AuditTrustAttestationException("Audit trust attestation signing is disabled."));
        return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

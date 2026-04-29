package com.frauddetection.alert.audit.external;

public record SignedAuditAnchorPayload(
        String signatureStatus,
        String signatureAlgorithm,
        String signature,
        String keyId,
        java.time.Instant signedAt,
        String signingAuthority,
        String signedPayloadHash
) {
    public static SignedAuditAnchorPayload unsigned() {
        return new SignedAuditAnchorPayload("UNSIGNED", null, null, null, null, null, null);
    }

    public static SignedAuditAnchorPayload unavailable() {
        return new SignedAuditAnchorPayload("SIGNATURE_UNAVAILABLE", null, null, null, null, null, null);
    }

    public static SignedAuditAnchorPayload failed() {
        return new SignedAuditAnchorPayload("SIGNATURE_FAILED", null, null, null, null, null, null);
    }
}

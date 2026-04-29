package com.frauddetection.alert.audit.external;

public record AuditTrustSignatureVerificationResult(
        String status,
        String reasonCode
) {
    public static AuditTrustSignatureVerificationResult valid() {
        return new AuditTrustSignatureVerificationResult("VALID", null);
    }

    public static AuditTrustSignatureVerificationResult invalid(String reasonCode) {
        return new AuditTrustSignatureVerificationResult("INVALID", reasonCode == null ? "SIGNATURE_INVALID" : reasonCode);
    }

    public static AuditTrustSignatureVerificationResult unavailable() {
        return new AuditTrustSignatureVerificationResult("UNAVAILABLE", "TRUST_AUTHORITY_UNAVAILABLE");
    }

    public static AuditTrustSignatureVerificationResult unknownKey() {
        return new AuditTrustSignatureVerificationResult("UNKNOWN_KEY", "UNKNOWN_KEY");
    }

    public static AuditTrustSignatureVerificationResult keyRevoked() {
        return new AuditTrustSignatureVerificationResult("KEY_REVOKED", "KEY_REVOKED");
    }

    public static AuditTrustSignatureVerificationResult unsigned() {
        return new AuditTrustSignatureVerificationResult("UNSIGNED", null);
    }
}

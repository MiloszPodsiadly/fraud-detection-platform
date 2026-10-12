package com.frauddetection.alert.audit.external;

final class ExternalSignaturePolicyEvaluator {

    private final AuditTrustAuthorityProperties properties;

    ExternalSignaturePolicyEvaluator(AuditTrustAuthorityProperties properties) {
        this.properties = properties == null ? new AuditTrustAuthorityProperties() : properties;
    }

    Result evaluate(AuditTrustSignatureVerificationResult verification) {
        String verificationStatus = verification == null ? "UNAVAILABLE" : verification.status();
        if (!properties.isEnabled()) {
            if ("INVALID".equals(verificationStatus)
                    || "UNKNOWN_KEY".equals(verificationStatus)
                    || "KEY_REVOKED".equals(verificationStatus)) {
                return new Result("INVALID", "SIGNATURE_" + verificationStatus, "Signature verification failed.");
            }
            return Result.valid();
        }
        boolean required = properties.isSigningRequired();
        return switch (verificationStatus) {
            case "VALID" -> Result.valid();
            case "UNSIGNED" -> new Result(
                    required ? "INVALID" : "PARTIAL",
                    required ? "SIGNATURE_UNSIGNED_REQUIRED" : "SIGNATURE_UNSIGNED",
                    "External anchor signature is missing."
            );
            case "UNAVAILABLE" -> new Result(
                    required ? "INVALID" : "PARTIAL",
                    required ? "SIGNATURE_UNAVAILABLE_REQUIRED" : "SIGNATURE_UNAVAILABLE",
                    "Trust authority signature verification is unavailable."
            );
            case "UNKNOWN_KEY", "KEY_REVOKED", "INVALID" -> new Result(
                    "INVALID",
                    "SIGNATURE_" + verificationStatus,
                    "Signature verification failed."
            );
            default -> new Result("INVALID", "SIGNATURE_INVALID", "Signature verification failed.");
        };
    }

    record Result(String status, String reasonCode, String message) {
        static Result valid() {
            return new Result("VALID", null, null);
        }
    }
}

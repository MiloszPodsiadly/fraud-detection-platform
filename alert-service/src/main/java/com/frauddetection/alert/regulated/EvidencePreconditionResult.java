package com.frauddetection.alert.regulated;

public record EvidencePreconditionResult(
        EvidencePreconditionStatus status,
        String reasonCode
) {

    public static EvidencePreconditionResult passed() {
        return new EvidencePreconditionResult(EvidencePreconditionStatus.PASSED, null);
    }

    public static EvidencePreconditionResult rejectedEvidenceUnavailable(String reasonCode) {
        return new EvidencePreconditionResult(EvidencePreconditionStatus.REJECTED_EVIDENCE_UNAVAILABLE, reasonCode);
    }

    public static EvidencePreconditionResult failedBusinessValidation(String reasonCode) {
        return new EvidencePreconditionResult(EvidencePreconditionStatus.FAILED_BUSINESS_VALIDATION, reasonCode);
    }

    public static EvidencePreconditionResult finalizeRecoveryRequired(String reasonCode) {
        return new EvidencePreconditionResult(EvidencePreconditionStatus.FINALIZE_RECOVERY_REQUIRED, reasonCode);
    }
}

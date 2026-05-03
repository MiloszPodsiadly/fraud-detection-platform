package com.frauddetection.alert.regulated;

import java.util.List;

public record EvidencePreconditionResult(
        EvidencePreconditionGateVersion gateVersion,
        EvidencePreconditionStatus status,
        String reasonCode,
        List<String> checkedPreconditions,
        List<String> skippedPreconditions
) {

    public static EvidencePreconditionResult satisfied(List<String> checkedPreconditions, List<String> skippedPreconditions) {
        return new EvidencePreconditionResult(
                EvidencePreconditionGateVersion.LOCAL_EVIDENCE_GATE_V1,
                EvidencePreconditionStatus.SATISFIED,
                null,
                List.copyOf(checkedPreconditions),
                List.copyOf(skippedPreconditions)
        );
    }

    public static EvidencePreconditionResult rejectedEvidenceUnavailable(
            String reasonCode,
            List<String> checkedPreconditions,
            List<String> skippedPreconditions
    ) {
        return result(EvidencePreconditionStatus.REJECTED_EVIDENCE_UNAVAILABLE, reasonCode, checkedPreconditions, skippedPreconditions);
    }

    public static EvidencePreconditionResult failedBusinessValidation(
            String reasonCode,
            List<String> checkedPreconditions,
            List<String> skippedPreconditions
    ) {
        return result(EvidencePreconditionStatus.FAILED_BUSINESS_VALIDATION, reasonCode, checkedPreconditions, skippedPreconditions);
    }

    public static EvidencePreconditionResult finalizeRecoveryRequired(
            String reasonCode,
            List<String> checkedPreconditions,
            List<String> skippedPreconditions
    ) {
        return result(EvidencePreconditionStatus.FINALIZE_RECOVERY_REQUIRED, reasonCode, checkedPreconditions, skippedPreconditions);
    }

    private static EvidencePreconditionResult result(
            EvidencePreconditionStatus status,
            String reasonCode,
            List<String> checkedPreconditions,
            List<String> skippedPreconditions
    ) {
        return new EvidencePreconditionResult(
                EvidencePreconditionGateVersion.LOCAL_EVIDENCE_GATE_V1,
                status,
                reasonCode,
                List.copyOf(checkedPreconditions),
                List.copyOf(skippedPreconditions)
        );
    }
}

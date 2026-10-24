package com.frauddetection.alert.governance.modelpromotion;

public record ModelPromotionPolicyTrustVerification(
        Status status,
        ModelPromotionPolicyAuthorityVerification signature,
        ModelPromotionPolicyApprovalEvidenceVerification approvalEvidence) {
    public enum Status { FULLY_AUTHORIZED, UNVERIFIED, UNAVAILABLE }
}

package com.frauddetection.alert.governance.modelpromotion;

public record ModelPromotionPolicyApprovalEvidenceVerification(Status status, Reason reason) {
    public enum Status { APPROVAL_EVIDENCE_VERIFIED, UNVERIFIED, UNAVAILABLE }
    public enum Reason { NONE, EVIDENCE_MISSING, EVIDENCE_INVALID, TRUST_UNAVAILABLE, STORE_UNAVAILABLE }

    public static ModelPromotionPolicyApprovalEvidenceVerification verified() {
        return new ModelPromotionPolicyApprovalEvidenceVerification(Status.APPROVAL_EVIDENCE_VERIFIED, Reason.NONE);
    }

    public static ModelPromotionPolicyApprovalEvidenceVerification invalid(Reason reason) {
        return new ModelPromotionPolicyApprovalEvidenceVerification(Status.UNVERIFIED, reason);
    }

    public static ModelPromotionPolicyApprovalEvidenceVerification unavailable(Reason reason) {
        return new ModelPromotionPolicyApprovalEvidenceVerification(Status.UNAVAILABLE, reason);
    }
}

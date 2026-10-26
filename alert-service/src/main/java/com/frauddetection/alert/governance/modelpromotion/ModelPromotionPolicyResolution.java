package com.frauddetection.alert.governance.modelpromotion;

public record ModelPromotionPolicyResolution(
        Status status,
        Reason reason,
        ModelPromotionPolicy policy
) {

    public ModelPromotionPolicyResolution {
        if (status == null || reason == null) {
            throw new IllegalArgumentException("status and reason are required");
        }
        if ((status == Status.AVAILABLE) != (policy != null)) {
            throw new IllegalArgumentException("Only AVAILABLE resolution may contain a policy");
        }
        if ((status == Status.AVAILABLE) != (reason == Reason.NONE)) {
            throw new IllegalArgumentException("Only AVAILABLE resolution may use NONE reason");
        }
    }

    public static ModelPromotionPolicyResolution available(ModelPromotionPolicy policy) {
        return new ModelPromotionPolicyResolution(Status.AVAILABLE, Reason.NONE, policy);
    }

    public static ModelPromotionPolicyResolution missing() {
        return new ModelPromotionPolicyResolution(Status.MISSING, Reason.APPROVED_POLICY_NOT_FOUND, null);
    }

    public static ModelPromotionPolicyResolution unavailable(Reason reason) {
        return new ModelPromotionPolicyResolution(Status.UNAVAILABLE, reason, null);
    }

    public enum Status {
        AVAILABLE,
        MISSING,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        APPROVED_POLICY_NOT_FOUND,
        POLICY_STORE_UNAVAILABLE,
        POLICY_DIGEST_INVALID,
        POLICY_SCOPE_MISMATCH,
        POLICY_NOT_EFFECTIVE,
        POLICY_CONTRACT_INVALID,
        POLICY_AUTHORITY_UNVERIFIED,
        POLICY_AUTHORITY_UNAVAILABLE,
        POLICY_APPROVAL_EVIDENCE_UNVERIFIED,
        POLICY_APPROVAL_EVIDENCE_UNAVAILABLE
    }
}

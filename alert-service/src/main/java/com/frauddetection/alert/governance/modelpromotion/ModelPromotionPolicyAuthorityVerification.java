package com.frauddetection.alert.governance.modelpromotion;

public record ModelPromotionPolicyAuthorityVerification(Status status, Reason reason) {

    public ModelPromotionPolicyAuthorityVerification {
        if (status == null || reason == null
                || (status == Status.VERIFIED && reason != Reason.NONE)
                || (status == Status.UNAVAILABLE && reason != Reason.POLICY_TRUST_UNAVAILABLE)
                || (status == Status.REJECTED
                    && (reason == Reason.NONE || reason == Reason.POLICY_TRUST_UNAVAILABLE))) {
            throw new IllegalArgumentException("verification status and reason are inconsistent");
        }
    }

    public static ModelPromotionPolicyAuthorityVerification verified() {
        return new ModelPromotionPolicyAuthorityVerification(Status.VERIFIED, Reason.NONE);
    }

    public static ModelPromotionPolicyAuthorityVerification rejected() {
        return rejected(Reason.POLICY_SIGNATURE_INVALID);
    }

    public static ModelPromotionPolicyAuthorityVerification rejected(Reason reason) {
        return new ModelPromotionPolicyAuthorityVerification(Status.REJECTED, reason);
    }

    public static ModelPromotionPolicyAuthorityVerification unavailable() {
        return new ModelPromotionPolicyAuthorityVerification(Status.UNAVAILABLE, Reason.POLICY_TRUST_UNAVAILABLE);
    }

    public enum Status {
        VERIFIED,
        REJECTED,
        UNAVAILABLE
    }

    public enum Reason {
        NONE,
        POLICY_SIGNATURE_INVALID,
        POLICY_ISSUER_UNAUTHORIZED,
        POLICY_KEY_UNKNOWN,
        POLICY_KEY_RETIRED,
        POLICY_KEY_REVOKED,
        POLICY_KEY_EXPIRED,
        POLICY_KEY_NOT_YET_VALID,
        POLICY_TRUST_UNAVAILABLE,
        POLICY_TIME_INVALID,
        POLICY_EXPIRED,
        POLICY_SIGNATURE_FUTURE_ISSUED,
        POLICY_CONTRACT_INVALID
    }
}

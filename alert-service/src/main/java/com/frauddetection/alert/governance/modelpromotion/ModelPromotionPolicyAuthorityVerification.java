package com.frauddetection.alert.governance.modelpromotion;

public record ModelPromotionPolicyAuthorityVerification(Status status) {

    public ModelPromotionPolicyAuthorityVerification {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }

    public static ModelPromotionPolicyAuthorityVerification verified() {
        return new ModelPromotionPolicyAuthorityVerification(Status.VERIFIED);
    }

    public static ModelPromotionPolicyAuthorityVerification rejected() {
        return new ModelPromotionPolicyAuthorityVerification(Status.REJECTED);
    }

    public static ModelPromotionPolicyAuthorityVerification unavailable() {
        return new ModelPromotionPolicyAuthorityVerification(Status.UNAVAILABLE);
    }

    public enum Status {
        VERIFIED,
        REJECTED,
        UNAVAILABLE
    }
}

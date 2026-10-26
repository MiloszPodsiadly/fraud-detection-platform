package com.frauddetection.alert.governance.modelpromotion;

@FunctionalInterface
public interface ModelPromotionPolicyApprovalEvidenceVerifier {
    ModelPromotionPolicyApprovalEvidenceVerification verify(ModelPromotionPolicy policy);
}

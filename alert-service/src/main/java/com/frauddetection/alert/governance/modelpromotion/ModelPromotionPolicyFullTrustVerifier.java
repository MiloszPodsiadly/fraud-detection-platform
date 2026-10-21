package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

/** Both controls must be current before an institutional policy can authorize a new decision. */
public final class ModelPromotionPolicyFullTrustVerifier {
    private final ModelPromotionPolicyAuthorityVerifier signatures;
    private final ModelPromotionPolicyApprovalEvidenceVerifier approvals;

    public ModelPromotionPolicyFullTrustVerifier(ModelPromotionPolicyAuthorityVerifier signatures,
            ModelPromotionPolicyApprovalEvidenceVerifier approvals) {
        this.signatures = Objects.requireNonNull(signatures);
        this.approvals = Objects.requireNonNull(approvals);
    }

    public ModelPromotionPolicyTrustVerification verify(ModelPromotionPolicy policy) {
        ModelPromotionPolicyAuthorityVerification signature = signatures.verify(policy);
        ModelPromotionPolicyApprovalEvidenceVerification approval = approvals.verify(policy);
        ModelPromotionPolicyTrustVerification.Status status;
        if (signature.status() == ModelPromotionPolicyAuthorityVerification.Status.VERIFIED
                && approval.status() == ModelPromotionPolicyApprovalEvidenceVerification.Status.APPROVAL_EVIDENCE_VERIFIED) {
            status = ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED;
        } else if (signature.status() == ModelPromotionPolicyAuthorityVerification.Status.UNAVAILABLE
                || approval.status() == ModelPromotionPolicyApprovalEvidenceVerification.Status.UNAVAILABLE) {
            status = ModelPromotionPolicyTrustVerification.Status.UNAVAILABLE;
        } else {
            status = ModelPromotionPolicyTrustVerification.Status.UNVERIFIED;
        }
        return new ModelPromotionPolicyTrustVerification(status, signature, approval);
    }
}

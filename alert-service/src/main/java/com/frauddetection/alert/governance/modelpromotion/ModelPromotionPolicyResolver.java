package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class ModelPromotionPolicyResolver {

    private final ModelPromotionPolicyStore policyStore;
    private final ModelPromotionPolicyValidator validator;
    private final ModelPromotionPolicyFullTrustVerifier fullTrustVerifier;

    public ModelPromotionPolicyResolver(
            ModelPromotionPolicyStore policyStore,
            ModelPromotionPolicyValidator validator,
            ModelPromotionPolicyFullTrustVerifier fullTrustVerifier
    ) {
        this.policyStore = Objects.requireNonNull(policyStore);
        this.validator = Objects.requireNonNull(validator);
        this.fullTrustVerifier = Objects.requireNonNull(fullTrustVerifier);
    }

    public ModelPromotionPolicyResolution resolve(
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            Instant assessmentTime
    ) {
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
        Objects.requireNonNull(assessmentTime, "assessmentTime is required");
        try {
            var stored = policyStore.findPolicy(governanceScope, intendedUse, assessmentTime);
            if (stored.isEmpty()) {
                return ModelPromotionPolicyResolution.missing();
            }
            ModelPromotionPolicy policy = stored.orElseThrow();
            validator.validate(policy, governanceScope, intendedUse, assessmentTime);
            if (policy.content().provenance().authority()
                    == ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED) {
                ModelPromotionPolicyTrustVerification verification = fullTrustVerifier.verify(policy);
                if (verification.status() != ModelPromotionPolicyTrustVerification.Status.FULLY_AUTHORIZED) {
                    return ModelPromotionPolicyResolution.unavailable(trustFailureReason(verification));
                }
            }
            return ModelPromotionPolicyResolution.available(policy);
        } catch (ModelPromotionPolicyValidator.InvalidPolicyException exception) {
            return ModelPromotionPolicyResolution.unavailable(exception.reason());
        } catch (ModelPromotionPolicyStoreUnavailableException exception) {
            return ModelPromotionPolicyResolution.unavailable(
                    ModelPromotionPolicyResolution.Reason.POLICY_STORE_UNAVAILABLE
            );
        }
    }

    private static ModelPromotionPolicyResolution.Reason trustFailureReason(
            ModelPromotionPolicyTrustVerification verification) {
        if (verification.signature().status() == ModelPromotionPolicyAuthorityVerification.Status.UNAVAILABLE) {
            return ModelPromotionPolicyResolution.Reason.POLICY_AUTHORITY_UNAVAILABLE;
        }
        if (verification.signature().status() != ModelPromotionPolicyAuthorityVerification.Status.VERIFIED) {
            return ModelPromotionPolicyResolution.Reason.POLICY_AUTHORITY_UNVERIFIED;
        }
        return verification.approvalEvidence().status()
                == ModelPromotionPolicyApprovalEvidenceVerification.Status.UNAVAILABLE
                ? ModelPromotionPolicyResolution.Reason.POLICY_APPROVAL_EVIDENCE_UNAVAILABLE
                : ModelPromotionPolicyResolution.Reason.POLICY_APPROVAL_EVIDENCE_UNVERIFIED;
    }
}

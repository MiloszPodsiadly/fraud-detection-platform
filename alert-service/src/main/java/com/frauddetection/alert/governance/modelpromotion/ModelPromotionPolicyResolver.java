package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class ModelPromotionPolicyResolver {

    private final ModelPromotionPolicyStore policyStore;
    private final ModelPromotionPolicyValidator validator;
    private final ModelPromotionPolicyAuthorityVerifier authorityVerifier;

    public ModelPromotionPolicyResolver(
            ModelPromotionPolicyStore policyStore,
            ModelPromotionPolicyValidator validator,
            ModelPromotionPolicyAuthorityVerifier authorityVerifier
    ) {
        this.policyStore = Objects.requireNonNull(policyStore);
        this.validator = Objects.requireNonNull(validator);
        this.authorityVerifier = Objects.requireNonNull(authorityVerifier);
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
                ModelPromotionPolicyAuthorityVerification verification = authorityVerifier.verify(policy);
                if (verification.status() != ModelPromotionPolicyAuthorityVerification.Status.VERIFIED) {
                    return ModelPromotionPolicyResolution.unavailable(
                            verification.status() == ModelPromotionPolicyAuthorityVerification.Status.UNAVAILABLE
                                    ? ModelPromotionPolicyResolution.Reason.POLICY_AUTHORITY_UNAVAILABLE
                                    : ModelPromotionPolicyResolution.Reason.POLICY_AUTHORITY_UNVERIFIED
                    );
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
}

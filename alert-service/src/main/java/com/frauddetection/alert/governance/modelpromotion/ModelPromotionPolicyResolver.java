package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class ModelPromotionPolicyResolver {

    private final ModelPromotionPolicyStore policyStore;
    private final ModelPromotionPolicyValidator validator;

    public ModelPromotionPolicyResolver(
            ModelPromotionPolicyStore policyStore,
            ModelPromotionPolicyValidator validator
    ) {
        this.policyStore = Objects.requireNonNull(policyStore);
        this.validator = Objects.requireNonNull(validator);
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
            var stored = policyStore.findPolicy(governanceScope, intendedUse);
            if (stored.isEmpty()) {
                return ModelPromotionPolicyResolution.missing();
            }
            ModelPromotionPolicy policy = stored.orElseThrow();
            validator.validate(policy, governanceScope, intendedUse, assessmentTime);
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

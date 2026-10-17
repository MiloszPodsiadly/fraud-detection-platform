package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.policy;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.policyValidator;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

class ModelPromotionPolicyResolverTest {

    @Test
    void missingApprovedPolicyFailsClosed() {
        ModelPromotionPolicyResolution result = resolver((scope, use) -> Optional.empty())
                .resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.MISSING);
        assertThat(result.reason()).isEqualTo(
                ModelPromotionPolicyResolution.Reason.APPROVED_POLICY_NOT_FOUND
        );
        assertThat(result.policy()).isNull();
    }

    @Test
    void modifiedPolicyDigestFailsClosed() {
        ModelPromotionPolicy valid = policy();
        ModelPromotionPolicy modified = new ModelPromotionPolicy(valid.content(), "f".repeat(64));

        ModelPromotionPolicyResolution result = resolver((scope, use) -> Optional.of(modified))
                .resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_DIGEST_INVALID);
    }

    @Test
    void stalePolicyIsNotSilentlyApplied() {
        ModelPromotionPolicy stale = ModelPromotionAssessmentTestFixtures.policy(
                ModelPromotionAssessmentTestFixtures.defaultRules(),
                "2025-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
        );

        ModelPromotionPolicyResolution result = resolver((scope, use) -> Optional.of(stale))
                .resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_NOT_EFFECTIVE);
    }

    @Test
    void policyStoreFailureRemainsDistinctFromMissingPolicy() {
        ModelPromotionPolicyResolver resolver = resolver((scope, use) -> {
            throw new ModelPromotionPolicyStoreUnavailableException("private detail");
        });

        ModelPromotionPolicyResolution result = resolver.resolve(
                scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, Instant.parse("2026-06-11T00:00:00Z")
        );

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_STORE_UNAVAILABLE);
    }

    private ModelPromotionPolicyResolver resolver(ModelPromotionPolicyStore store) {
        return new ModelPromotionPolicyResolver(store, policyValidator());
    }
}

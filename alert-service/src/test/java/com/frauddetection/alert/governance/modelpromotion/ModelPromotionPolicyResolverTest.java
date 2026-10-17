package com.frauddetection.alert.governance.modelpromotion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.ASSESSMENT_TIME;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.policy;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.policyValidator;
import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionAssessmentTestFixtures.scope;
import static org.assertj.core.api.Assertions.assertThat;

class ModelPromotionPolicyResolverTest {

    @Test
    void missingApprovedPolicyFailsClosed() {
        ModelPromotionPolicyResolution result = resolver((scope, use, assessmentTime) -> Optional.empty())
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

        ModelPromotionPolicyResolution result = resolver((scope, use, assessmentTime) -> Optional.of(modified))
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

        ModelPromotionPolicyResolution result = resolver((scope, use, assessmentTime) -> Optional.of(stale))
                .resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_NOT_EFFECTIVE);
    }

    @Test
    void policyStoreFailureRemainsDistinctFromMissingPolicy() {
        ModelPromotionPolicyResolver resolver = resolver((scope, use, assessmentTime) -> {
            throw new ModelPromotionPolicyStoreUnavailableException("private detail");
        });

        ModelPromotionPolicyResolution result = resolver.resolve(
                scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, Instant.parse("2026-06-11T00:00:00Z")
        );

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_STORE_UNAVAILABLE);
    }

    @Test
    void institutionallyLabeledPolicyWithoutVerifiedAuthorityFailsClosed() {
        ModelPromotionPolicyResolution result = resolver(
                (scope, use, assessmentTime) -> Optional.of(institutionalPolicy()),
                policy -> ModelPromotionPolicyAuthorityVerification.rejected()
        ).resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_AUTHORITY_UNVERIFIED);
    }

    @Test
    void institutionalAuthorizationIssuedAfterEffectiveStartFailsClosed() {
        ModelPromotionPolicyResolution result = resolver(
                (scope, use, assessmentTime) -> Optional.of(institutionalPolicy("2026-01-02T00:00:00Z"))
        ).resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_CONTRACT_INVALID);
    }

    @Test
    void policyForAnotherScopeFailsClosed() {
        ModelPromotionPolicyResolution result = resolver((scope, use, assessmentTime) -> Optional.of(policy()))
                .resolve(
                        new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION, ModelGovernanceEnvironment.TEST),
                        ModelPromotionIntendedUse.FRAUD_RISK_SCORING,
                        ASSESSMENT_TIME
                );

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_SCOPE_MISMATCH);
    }

    @Test
    void nonWaivableCriteriaCannotBePolicyDisabled() {
        var rules = new ArrayList<>(ModelPromotionAssessmentTestFixtures.defaultRules());
        rules.set(ModelPromotionCriterion.EXACT_SHA_MODEL_CARD.ordinal(), new ModelPromotionPolicy.CriterionRule(
                ModelPromotionCriterion.EXACT_SHA_MODEL_CARD,
                ModelPromotionPolicy.CriterionApplicability.NOT_APPLICABLE_AUTHORIZED,
                java.util.Map.of(),
                604800,
                "VERIFIED_REPORTS_AND_MANIFESTS",
                ModelPromotionCriterion.EXACT_SHA_MODEL_CARD.evidenceSource(),
                ModelPromotionCriterion.EXACT_SHA_MODEL_CARD.metricSemantics(),
                ModelPromotionCriterion.EXACT_SHA_MODEL_CARD.populationDefinition(),
                ModelPromotionCriterion.EXACT_SHA_MODEL_CARD.denominatorDefinition(),
                "not-applicable-for-test"
        ));
        ModelPromotionPolicy disabled = ModelPromotionAssessmentTestFixtures.policy(
                rules,
                "2026-01-01T00:00:00Z",
                "2027-01-01T00:00:00Z"
        );

        ModelPromotionPolicyResolution result = resolver((scope, use, assessmentTime) -> Optional.of(disabled))
                .resolve(scope(), ModelPromotionIntendedUse.FRAUD_RISK_SCORING, ASSESSMENT_TIME);

        assertThat(result.status()).isEqualTo(ModelPromotionPolicyResolution.Status.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(ModelPromotionPolicyResolution.Reason.POLICY_CONTRACT_INVALID);
    }

    private ModelPromotionPolicyResolver resolver(ModelPromotionPolicyStore store) {
        return resolver(store, policy -> ModelPromotionPolicyAuthorityVerification.verified());
    }

    private ModelPromotionPolicyResolver resolver(
            ModelPromotionPolicyStore store,
            ModelPromotionPolicyAuthorityVerifier authorityVerifier
    ) {
        return new ModelPromotionPolicyResolver(
                store,
                policyValidator(),
                authorityVerifier
        );
    }

    private ModelPromotionPolicy institutionalPolicy() {
        return institutionalPolicy("2025-12-31T00:00:00Z");
    }

    private ModelPromotionPolicy institutionalPolicy(String authorizationIssuedAt) {
        ModelPromotionPolicy baseline = policy();
        ModelPromotionPolicy.Content source = baseline.content();
        ModelPromotionPolicy.Content content = new ModelPromotionPolicy.Content(
                source.policyVersion(),
                source.policyId(),
                source.revision(),
                source.governanceScope(),
                source.intendedUse(),
                new ModelPromotionPolicy.PolicyProvenance(
                        ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED,
                        "institutional-policy-author",
                        "institutional-risk-committee",
                        "2025-12-31T00:00:00Z",
                        "institutional-approval-1"
                ),
                source.effectiveFromInclusive(),
                source.effectiveToExclusive(),
                source.criteria()
        );
        String digest = policyValidator().canonicalSha256(content);
        return new ModelPromotionPolicy(
                content,
                digest,
                new ModelPromotionPolicy.PolicyAuthorizationEvidence(
                        "institutional-policy-authority",
                        "authority-key-1",
                        "detached-signature",
                        digest,
                        authorizationIssuedAt
                )
        );
    }
}

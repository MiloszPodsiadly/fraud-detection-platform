package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyResolution.Reason;

@Component
public class ModelPromotionPolicyValidator {

    private static final Set<String> RATE_THRESHOLDS = Set.of(
            "minimumMlPrecision", "minimumDeltaVsRules", "maximumRate"
    );
    private static final Set<String> INTEGER_THRESHOLDS = Set.of(
            "minimumEvaluatedRecords", "minimumPositiveLabels", "minimumNegativeLabels",
            "alertReviewBudget", "maximumCriticalRegressions", "minimumSegmentsEvaluated",
            "minimumSampleSize", "minimumDenominator", "maximumCriticalLineageGaps"
    );
    private static final Set<ModelPromotionCriterion> NON_WAIVABLE_CRITERIA = EnumSet.of(
            ModelPromotionCriterion.COMPLETE_EVALUATION_PROVENANCE,
            ModelPromotionCriterion.EXACT_SHA_MODEL_CARD,
            ModelPromotionCriterion.IDENTITY_AND_LINEAGE_INTEGRITY
    );

    private final ObjectMapper objectMapper;

    public ModelPromotionPolicyValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(
            ModelPromotionPolicy policy,
            ModelGovernanceScope requestedScope,
            ModelPromotionIntendedUse requestedUse,
            Instant assessmentTime
    ) {
        if (!canonicalSha256(policy.content()).equals(policy.canonicalSha256())) {
            throw invalid(Reason.POLICY_DIGEST_INVALID);
        }
        ModelPromotionPolicy.Content content = policy.content();
        if (!content.governanceScope().equals(requestedScope) || content.intendedUse() != requestedUse) {
            throw invalid(Reason.POLICY_SCOPE_MISMATCH);
        }
        Instant effectiveFrom = timestamp(content.effectiveFromInclusive());
        Instant effectiveTo = timestamp(content.effectiveToExclusive());
        Instant approvedAt = timestamp(content.provenance().approvedAt());
        if (!effectiveFrom.isBefore(effectiveTo) || approvedAt.isAfter(effectiveFrom)
                || assessmentTime.isBefore(effectiveFrom) || !assessmentTime.isBefore(effectiveTo)) {
            throw invalid(Reason.POLICY_NOT_EFFECTIVE);
        }
        if (content.provenance().authority() == ModelPromotionPolicy.PolicyAuthority.INSTITUTIONAL_APPROVED
                && timestamp(policy.authorizationEvidence().issuedAt()).isAfter(effectiveFrom)) {
            throw invalid(Reason.POLICY_CONTRACT_INVALID);
        }
        EnumSet<ModelPromotionCriterion> observed = EnumSet.noneOf(ModelPromotionCriterion.class);
        for (ModelPromotionPolicy.CriterionRule rule : content.criteria()) {
            if (!observed.add(rule.criterion())) {
                throw invalid(Reason.POLICY_CONTRACT_INVALID);
            }
            if (rule.applicability() == ModelPromotionPolicy.CriterionApplicability.NOT_APPLICABLE_AUTHORIZED
                    && isNonWaivable(rule.criterion())) {
                throw invalid(Reason.POLICY_CONTRACT_INVALID);
            }
            validateThresholds(rule);
        }
        if (!observed.equals(EnumSet.allOf(ModelPromotionCriterion.class))) {
            throw invalid(Reason.POLICY_CONTRACT_INVALID);
        }
    }

    public String canonicalSha256(ModelPromotionPolicy.Content content) {
        return GovernanceCanonicalJson.sha256(objectMapper, content);
    }

    static boolean isNonWaivable(ModelPromotionCriterion criterion) {
        return NON_WAIVABLE_CRITERIA.contains(criterion);
    }

    private void validateThresholds(ModelPromotionPolicy.CriterionRule rule) {
        rule.thresholds().forEach((name, value) -> {
            if (value.signum() < 0) {
                throw invalid(Reason.POLICY_CONTRACT_INVALID);
            }
            if (RATE_THRESHOLDS.contains(name) && value.compareTo(BigDecimal.ONE) > 0) {
                throw invalid(Reason.POLICY_CONTRACT_INVALID);
            }
            if (INTEGER_THRESHOLDS.contains(name)) {
                try {
                    value.intValueExact();
                } catch (ArithmeticException exception) {
                    throw invalid(Reason.POLICY_CONTRACT_INVALID);
                }
            }
        });
    }

    private Instant timestamp(String value) {
        try {
            return GovernanceTimestampContract.parse(value, "policy timestamp");
        } catch (IllegalArgumentException exception) {
            throw invalid(Reason.POLICY_CONTRACT_INVALID);
        }
    }

    private InvalidPolicyException invalid(Reason reason) {
        return new InvalidPolicyException(reason);
    }

    static final class InvalidPolicyException extends RuntimeException {

        private final Reason reason;

        private InvalidPolicyException(Reason reason) {
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }
}

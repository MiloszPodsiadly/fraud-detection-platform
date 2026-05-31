package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineContribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FraudEngineContributionSanitizer {

    public List<BoundedFraudEngineContributionSummary> sanitize(
            String engineId,
            List<FraudEngineContribution> source,
            FraudEngineAggregationPolicy policy,
            List<FraudEngineAggregationWarning> warnings
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<BoundedFraudEngineContributionSummary> sanitized = new ArrayList<>();
        for (FraudEngineContribution contribution : source) {
            if (sanitized.size() == policy.maxContributionsPerEngine()) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.CONTRIBUTION_LIMIT_APPLIED);
                break;
            }
            if (!isSafe(contribution)) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.CONTRIBUTION_UNSAFE_DROPPED);
                continue;
            }
            if (contribution.value() != null) {
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.CONTRIBUTION_VALUE_DROPPED);
            }
            String feature = contribution.feature();
            if (feature.length() > policy.maxStringLength()) {
                feature = FraudEngineAggregationSafety.truncate(feature, policy.maxStringLength());
                warn(warnings, policy, engineId, FraudEngineAggregationWarningCode.CONTRIBUTION_TEXT_TRUNCATED);
            }
            sanitized.add(new BoundedFraudEngineContributionSummary(
                    feature,
                    contribution.weight(),
                    contribution.direction()
            ));
        }
        sanitized.sort(Comparator.comparing(BoundedFraudEngineContributionSummary::feature));
        return List.copyOf(sanitized);
    }

    private boolean isSafe(FraudEngineContribution contribution) {
        return contribution != null
                && FraudEngineAggregationSafety.isSafe(contribution.feature())
                && FraudEngineAggregationSafety.isSafe(contribution.value());
    }

    private void warn(
            List<FraudEngineAggregationWarning> warnings,
            FraudEngineAggregationPolicy policy,
            String engineId,
            FraudEngineAggregationWarningCode code
    ) {
        FraudEngineAggregationSafety.addWarning(warnings, policy, engineId, code);
    }
}

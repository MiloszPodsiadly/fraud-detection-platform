package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineResult;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FraudEngineAggregationService {
    private static final Map<String, Integer> ENGINE_ORDER = Map.of(
            "rules.primary", 0,
            "ml.python.primary", 1
    );

    private final FraudEngineAggregationPolicy policy;
    private final FraudEngineReasonCodeNormalizer reasonCodeNormalizer = new FraudEngineReasonCodeNormalizer();
    private final FraudEngineEvidenceSanitizer evidenceSanitizer = new FraudEngineEvidenceSanitizer();
    private final FraudEngineContributionSanitizer contributionSanitizer = new FraudEngineContributionSanitizer();
    private final FraudEngineScoreDeltaCalculator scoreDeltaCalculator = new FraudEngineScoreDeltaCalculator();
    private final FraudEngineRiskMismatchCalculator riskMismatchCalculator = new FraudEngineRiskMismatchCalculator();
    private final FraudEngineAgreementAnalyzer agreementAnalyzer = new FraudEngineAgreementAnalyzer();
    private final FraudEngineStrongestSignalExtractor strongestSignalExtractor = new FraudEngineStrongestSignalExtractor();

    public FraudEngineAggregationService(FraudEngineAggregationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy is required");
    }

    public FraudEngineAggregationResult aggregate(FraudScoringOrchestrationResult orchestrationResult) {
        Objects.requireNonNull(orchestrationResult, "orchestrationResult is required");
        List<FraudEngineAggregationWarning> warnings = new ArrayList<>();
        validateEngineIdentities(orchestrationResult.engineResults());
        List<FraudEngineResult> orderedResults = orchestrationResult.engineResults().stream()
                .sorted(Comparator.comparingInt(result -> ENGINE_ORDER.getOrDefault(result.engineId(), Integer.MAX_VALUE)))
                .toList();
        if (orderedResults.size() > policy.maxEngineResults()) {
            FraudEngineAggregationSafety.addWarning(
                    warnings,
                    policy,
                    null,
                    FraudEngineAggregationWarningCode.ENGINE_RESULT_LIMIT_APPLIED
            );
            orderedResults = orderedResults.subList(0, policy.maxEngineResults());
        }
        List<NormalizedFraudEngineResult> normalized = orderedResults.stream()
                .map(result -> normalize(result, warnings))
                .toList();
        return new FraudEngineAggregationResult(
                normalized,
                agreementAnalyzer.analyze(normalized),
                scoreDeltaCalculator.calculate(normalized),
                riskMismatchCalculator.calculate(normalized),
                strongestSignalExtractor.extract(normalized, policy),
                warnings,
                orchestrationResult.generatedAt()
        );
    }

    private void validateEngineIdentities(List<FraudEngineResult> engineResults) {
        Set<String> seen = new HashSet<>();
        for (FraudEngineResult result : engineResults) {
            String engineId = result.engineId();
            if (engineId == null || !ENGINE_ORDER.containsKey(engineId)) {
                throw new IllegalArgumentException("AGGREGATION_UNKNOWN_ENGINE_ID");
            }
            if (!seen.add(engineId)) {
                throw new IllegalArgumentException("AGGREGATION_DUPLICATE_ENGINE_ID");
            }
        }
    }

    private NormalizedFraudEngineResult normalize(
            FraudEngineResult result,
            List<FraudEngineAggregationWarning> warnings
    ) {
        return new NormalizedFraudEngineResult(
                result.engineId(),
                result.engineType(),
                result.status(),
                result.score(),
                result.riskLevel(),
                result.confidence(),
                reasonCodeNormalizer.normalize(result.engineId(), result.reasonCodes(), policy, warnings),
                evidenceSanitizer.sanitize(result.engineId(), result.evidence(), policy, warnings),
                contributionSanitizer.sanitize(result.engineId(), result.contributions(), policy, warnings),
                result.latencyMs()
        );
    }
}

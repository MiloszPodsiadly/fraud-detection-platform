package com.frauddetection.scoring.service;

import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Primary
public class CompositeFraudScoringEngine implements FraudScoringEngine {

    private final RuleBasedFraudScoringEngine ruleBasedFraudScoringEngine;
    private final MlFraudScoringEngine mlFraudScoringEngine;
    private final ScoringProperties scoringProperties;

    public CompositeFraudScoringEngine(
            RuleBasedFraudScoringEngine ruleBasedFraudScoringEngine,
            MlFraudScoringEngine mlFraudScoringEngine,
            ScoringProperties scoringProperties
    ) {
        this.ruleBasedFraudScoringEngine = ruleBasedFraudScoringEngine;
        this.mlFraudScoringEngine = mlFraudScoringEngine;
        this.scoringProperties = scoringProperties;
    }

    @Override
    public FraudScoreResult score(FraudScoringRequest request) {
        return switch (scoringProperties.mode()) {
            case RULE_BASED -> ruleBasedFraudScoringEngine.score(request);
            case ML -> scoreWithMlFallback(request);
            case SHADOW -> scoreWithShadow(request);
            case COMPARE -> scoreWithComparison(request);
        };
    }

    private FraudScoreResult scoreWithMlFallback(FraudScoringRequest request) {
        FraudScoreResult mlResult = mlFraudScoringEngine.score(request);
        if (isModelAvailable(mlResult)) {
            return mlResult;
        }

        return withDiagnostics(ruleBasedFraudScoringEngine.score(request), Map.of(
                "mode", ScoringMode.ML.name(),
                "fallbackUsed", true,
                "fallbackReason", fallbackReason(mlResult),
                "mlModelName", mlResult.modelName(),
                "mlModelVersion", mlResult.modelVersion()
        ));
    }

    private FraudScoreResult scoreWithShadow(FraudScoringRequest request) {
        FraudScoreResult ruleResult = ruleBasedFraudScoringEngine.score(request);
        FraudScoreResult mlResult = mlFraudScoringEngine.score(request);

        return withDiagnostics(ruleResult, Map.of(
                "mode", ScoringMode.SHADOW.name(),
                "finalDecisionSource", "RULE_BASED",
                "shadowModelAvailable", isModelAvailable(mlResult),
                "shadowModelName", mlResult.modelName(),
                "shadowModelVersion", mlResult.modelVersion(),
                "shadowFraudScore", mlResult.fraudScore(),
                "shadowRiskLevel", mlResult.riskLevel().name(),
                "shadowFallbackReason", fallbackReason(mlResult)
        ));
    }

    private FraudScoreResult scoreWithComparison(FraudScoringRequest request) {
        FraudScoreResult ruleResult = ruleBasedFraudScoringEngine.score(request);
        FraudScoreResult mlResult = mlFraudScoringEngine.score(request);

        return withDiagnostics(ruleResult, Map.of(
                "mode", ScoringMode.COMPARE.name(),
                "finalDecisionSource", "RULE_BASED",
                "mlModelAvailable", isModelAvailable(mlResult),
                "mlModelName", mlResult.modelName(),
                "mlModelVersion", mlResult.modelVersion(),
                "mlFraudScore", mlResult.fraudScore(),
                "mlRiskLevel", mlResult.riskLevel().name(),
                "scoreDelta", ruleResult.fraudScore() - mlResult.fraudScore(),
                "riskLevelMatch", ruleResult.riskLevel() == mlResult.riskLevel(),
                "mlFallbackReason", fallbackReason(mlResult)
        ));
    }

    private boolean isModelAvailable(FraudScoreResult result) {
        return Boolean.TRUE.equals(result.explanationMetadata().get("modelAvailable"));
    }

    private String fallbackReason(FraudScoreResult result) {
        Object reason = result.explanationMetadata().get("fallbackReason");
        return reason == null ? "ML model result is unavailable." : reason.toString();
    }

    private FraudScoreResult withDiagnostics(FraudScoreResult result, Map<String, Object> diagnostics) {
        Map<String, Object> scoreDetails = new LinkedHashMap<>(result.scoreDetails());
        scoreDetails.put("mlDiagnostics", diagnostics);

        Map<String, Object> explanationMetadata = new LinkedHashMap<>(result.explanationMetadata());
        explanationMetadata.put("mlDiagnostics", diagnostics);

        return new FraudScoreResult(
                result.fraudScore(),
                result.riskLevel(),
                result.scoringStrategy(),
                result.modelName(),
                result.modelVersion(),
                result.inferenceTimestamp(),
                result.reasonCodes(),
                scoreDetails,
                result.featureSnapshot(),
                explanationMetadata,
                result.alertRecommended()
        );
    }
}

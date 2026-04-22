package com.frauddetection.scoring.service;

import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoreResult;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Primary
public class CompositeFraudScoringEngine implements FraudScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(CompositeFraudScoringEngine.class);

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

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("mode", ScoringMode.SHADOW.name());
        diagnostics.put("finalDecisionSource", "RULE_BASED");
        diagnostics.put("shadowModelAvailable", isModelAvailable(mlResult));
        diagnostics.put("shadowModelName", mlResult.modelName());
        diagnostics.put("shadowModelVersion", mlResult.modelVersion());
        diagnostics.put("shadowFraudScore", mlResult.fraudScore());
        diagnostics.put("shadowRiskLevel", mlResult.riskLevel().name());
        diagnostics.put("modelMonitoring", ModelMonitoringMetrics.from(ScoringMode.SHADOW, ruleResult, mlResult, isModelAvailable(mlResult)));
        if (!isModelAvailable(mlResult)) {
            diagnostics.put("shadowFallbackReason", fallbackReason(mlResult));
        }

        logModelMonitoring(request, diagnostics);
        return withDiagnostics(ruleResult, diagnostics);
    }

    private FraudScoreResult scoreWithComparison(FraudScoringRequest request) {
        FraudScoreResult ruleResult = ruleBasedFraudScoringEngine.score(request);
        FraudScoreResult mlResult = mlFraudScoringEngine.score(request);

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("mode", ScoringMode.COMPARE.name());
        diagnostics.put("finalDecisionSource", "RULE_BASED");
        diagnostics.put("mlModelAvailable", isModelAvailable(mlResult));
        diagnostics.put("mlModelName", mlResult.modelName());
        diagnostics.put("mlModelVersion", mlResult.modelVersion());
        diagnostics.put("mlFraudScore", mlResult.fraudScore());
        diagnostics.put("mlRiskLevel", mlResult.riskLevel().name());
        diagnostics.put("scoreDelta", score(ruleResult) - score(mlResult));
        diagnostics.put("riskLevelMatch", ruleResult.riskLevel() == mlResult.riskLevel());
        diagnostics.put("modelMonitoring", ModelMonitoringMetrics.from(ScoringMode.COMPARE, ruleResult, mlResult, isModelAvailable(mlResult)));
        if (!isModelAvailable(mlResult)) {
            diagnostics.put("mlFallbackReason", fallbackReason(mlResult));
        }

        logModelMonitoring(request, diagnostics);
        return withDiagnostics(ruleResult, diagnostics);
    }

    private boolean isModelAvailable(FraudScoreResult result) {
        return Boolean.TRUE.equals(result.explanationMetadata().get("modelAvailable"));
    }

    private String fallbackReason(FraudScoreResult result) {
        Object reason = result.explanationMetadata().get("fallbackReason");
        return reason == null ? "ML model result is unavailable." : reason.toString();
    }

    private double score(FraudScoreResult result) {
        return result.fraudScore() == null ? 0.0d : result.fraudScore();
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

    @SuppressWarnings("unchecked")
    private void logModelMonitoring(FraudScoringRequest request, Map<String, Object> diagnostics) {
        Map<String, Object> monitoring = (Map<String, Object>) diagnostics.get("modelMonitoring");
        log.atInfo()
                .addKeyValue("transactionId", request.event().transactionId())
                .addKeyValue("correlationId", request.event().correlationId())
                .addKeyValue("mode", monitoring.get("mode"))
                .addKeyValue("modelName", monitoring.get("modelName"))
                .addKeyValue("modelVersion", monitoring.get("modelVersion"))
                .addKeyValue("mlScoreBucket", monitoring.get("mlScoreBucket"))
                .addKeyValue("scoreDelta", monitoring.get("scoreDelta"))
                .addKeyValue("decisionDisagreement", monitoring.get("decisionDisagreement"))
                .addKeyValue("riskLevelMismatch", monitoring.get("riskLevelMismatch"))
                .log("Recorded ML model monitoring sample.");
    }
}

package com.frauddetection.scoring.service;

import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.domain.FraudScoreResult;

import java.util.LinkedHashMap;
import java.util.Map;

final class ModelMonitoringMetrics {

    private ModelMonitoringMetrics() {
    }

    static Map<String, Object> from(ScoringMode mode, FraudScoreResult ruleResult, FraudScoreResult mlResult, boolean modelAvailable) {
        double ruleScore = safeScore(ruleResult);
        Double mlScore = modelAvailable ? safeScore(mlResult) : null;
        boolean riskMismatch = modelAvailable && ruleResult.riskLevel() != mlResult.riskLevel();
        boolean decisionDisagreement = modelAvailable
                && Boolean.TRUE.equals(ruleResult.alertRecommended()) != Boolean.TRUE.equals(mlResult.alertRecommended());
        Double scoreDelta = modelAvailable ? ruleScore - mlScore : null;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mode", mode.name());
        metrics.put("modelAvailable", modelAvailable);
        metrics.put("modelName", mlResult.modelName());
        metrics.put("modelVersion", mlResult.modelVersion());
        metrics.put("finalDecisionSource", "RULE_BASED");
        metrics.put("ruleBasedScore", ruleScore);
        metrics.put("ruleBasedRiskLevel", ruleResult.riskLevel().name());
        metrics.put("ruleBasedScoreBucket", scoreBucket(ruleScore));
        metrics.put("mlScore", mlScore);
        metrics.put("mlRiskLevel", modelAvailable ? mlResult.riskLevel().name() : null);
        metrics.put("mlScoreBucket", modelAvailable ? scoreBucket(mlScore) : "UNAVAILABLE");
        metrics.put("scoreDelta", scoreDelta);
        metrics.put("absoluteScoreDelta", scoreDelta == null ? null : Math.abs(scoreDelta));
        metrics.put("decisionDisagreement", decisionDisagreement);
        metrics.put("decisionDisagreementSample", decisionDisagreement ? 1 : 0);
        metrics.put("riskLevelMismatch", riskMismatch);
        metrics.put("riskLevelMismatchSample", riskMismatch ? 1 : 0);
        metrics.put("prometheusSamples", prometheusSamples(
                mode,
                mlResult,
                modelAvailable,
                ruleScore,
                mlScore,
                scoreDelta,
                decisionDisagreement,
                riskMismatch
        ));
        metrics.put("modelPerformanceByVersion", modelPerformanceByVersion(
                mode,
                mlResult,
                modelAvailable,
                mlScore,
                decisionDisagreement,
                riskMismatch
        ));
        return metrics;
    }

    private static Map<String, Object> prometheusSamples(
            ScoringMode mode,
            FraudScoreResult mlResult,
            boolean modelAvailable,
            double ruleScore,
            Double mlScore,
            Double scoreDelta,
            boolean decisionDisagreement,
            boolean riskMismatch
    ) {
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("fraud_model_rule_score", sample(mode, mlResult, ruleScore));
        samples.put("fraud_model_ml_score", modelAvailable ? sample(mode, mlResult, mlScore) : unavailableSample(mode, mlResult));
        samples.put("fraud_model_score_delta", modelAvailable ? sample(mode, mlResult, scoreDelta) : unavailableSample(mode, mlResult));
        samples.put("fraud_model_disagreement", modelAvailable ? sample(mode, mlResult, decisionDisagreement ? 1.0d : 0.0d) : unavailableSample(mode, mlResult));
        samples.put("fraud_model_risk_level_mismatch", modelAvailable ? sample(mode, mlResult, riskMismatch ? 1.0d : 0.0d) : unavailableSample(mode, mlResult));
        samples.put("fraud_model_ml_score_bucket", modelAvailable ? bucketSample(mode, mlResult, mlScore) : unavailableBucketSample(mode, mlResult));
        samples.put("fraud_model_rule_score_bucket", bucketSample(mode, mlResult, ruleScore));
        return samples;
    }

    private static Map<String, Object> modelPerformanceByVersion(
            ScoringMode mode,
            FraudScoreResult mlResult,
            boolean modelAvailable,
            Double mlScore,
            boolean decisionDisagreement,
            boolean riskMismatch
    ) {
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("mode", mode.name());
        performance.put("modelName", mlResult.modelName());
        performance.put("modelVersion", mlResult.modelVersion());
        performance.put("modelAvailable", modelAvailable);
        performance.put("mlScore", mlScore);
        performance.put("mlRiskLevel", modelAvailable ? mlResult.riskLevel().name() : null);
        performance.put("decisionDisagreementSample", decisionDisagreement ? 1 : 0);
        performance.put("riskLevelMismatchSample", riskMismatch ? 1 : 0);
        return performance;
    }

    private static Map<String, Object> sample(ScoringMode mode, FraudScoreResult mlResult, Double value) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("value", value);
        sample.put("labels", labels(mode, mlResult));
        return sample;
    }

    private static Map<String, Object> bucketSample(ScoringMode mode, FraudScoreResult mlResult, Double score) {
        Map<String, Object> sample = sample(mode, mlResult, 1.0d);
        sample.put("bucket", scoreBucket(score));
        return sample;
    }

    private static Map<String, Object> unavailableSample(ScoringMode mode, FraudScoreResult mlResult) {
        Map<String, Object> sample = sample(mode, mlResult, null);
        sample.put("status", "UNAVAILABLE");
        return sample;
    }

    private static Map<String, Object> unavailableBucketSample(ScoringMode mode, FraudScoreResult mlResult) {
        Map<String, Object> sample = unavailableSample(mode, mlResult);
        sample.put("bucket", "UNAVAILABLE");
        return sample;
    }

    private static Map<String, Object> labels(ScoringMode mode, FraudScoreResult mlResult) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("mode", mode.name());
        labels.put("modelName", mlResult.modelName());
        labels.put("modelVersion", mlResult.modelVersion());
        return labels;
    }

    private static double safeScore(FraudScoreResult result) {
        return result.fraudScore() == null ? 0.0d : result.fraudScore();
    }

    private static String scoreBucket(double score) {
        if (score < 0.25d) {
            return "0.00-0.25";
        }
        if (score < 0.50d) {
            return "0.25-0.50";
        }
        if (score < 0.75d) {
            return "0.50-0.75";
        }
        if (score < 0.90d) {
            return "0.75-0.90";
        }
        return "0.90-1.00";
    }
}

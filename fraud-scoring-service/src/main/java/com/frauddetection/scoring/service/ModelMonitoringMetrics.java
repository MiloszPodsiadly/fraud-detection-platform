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
        double mlScore = safeScore(mlResult);
        boolean riskMismatch = ruleResult.riskLevel() != mlResult.riskLevel();
        boolean decisionDisagreement = Boolean.TRUE.equals(ruleResult.alertRecommended()) != Boolean.TRUE.equals(mlResult.alertRecommended());
        double scoreDelta = ruleScore - mlScore;

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
        metrics.put("mlRiskLevel", mlResult.riskLevel().name());
        metrics.put("mlScoreBucket", scoreBucket(mlScore));
        metrics.put("scoreDelta", scoreDelta);
        metrics.put("absoluteScoreDelta", Math.abs(scoreDelta));
        metrics.put("decisionDisagreement", decisionDisagreement);
        metrics.put("decisionDisagreementSample", decisionDisagreement ? 1 : 0);
        metrics.put("riskLevelMismatch", riskMismatch);
        metrics.put("riskLevelMismatchSample", riskMismatch ? 1 : 0);
        metrics.put("prometheusSamples", prometheusSamples(mode, mlResult, ruleScore, mlScore, scoreDelta, decisionDisagreement, riskMismatch));
        metrics.put("modelPerformanceByVersion", modelPerformanceByVersion(mode, mlResult, mlScore, decisionDisagreement, riskMismatch));
        return metrics;
    }

    private static Map<String, Object> prometheusSamples(
            ScoringMode mode,
            FraudScoreResult mlResult,
            double ruleScore,
            double mlScore,
            double scoreDelta,
            boolean decisionDisagreement,
            boolean riskMismatch
    ) {
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("fraud_model_rule_score", sample(mode, mlResult, ruleScore));
        samples.put("fraud_model_ml_score", sample(mode, mlResult, mlScore));
        samples.put("fraud_model_score_delta", sample(mode, mlResult, scoreDelta));
        samples.put("fraud_model_disagreement", sample(mode, mlResult, decisionDisagreement ? 1 : 0));
        samples.put("fraud_model_risk_level_mismatch", sample(mode, mlResult, riskMismatch ? 1 : 0));
        samples.put("fraud_model_ml_score_bucket", bucketSample(mode, mlResult, mlScore));
        samples.put("fraud_model_rule_score_bucket", bucketSample(mode, mlResult, ruleScore));
        return samples;
    }

    private static Map<String, Object> modelPerformanceByVersion(
            ScoringMode mode,
            FraudScoreResult mlResult,
            double mlScore,
            boolean decisionDisagreement,
            boolean riskMismatch
    ) {
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("mode", mode.name());
        performance.put("modelName", mlResult.modelName());
        performance.put("modelVersion", mlResult.modelVersion());
        performance.put("mlScore", mlScore);
        performance.put("mlRiskLevel", mlResult.riskLevel().name());
        performance.put("decisionDisagreementSample", decisionDisagreement ? 1 : 0);
        performance.put("riskLevelMismatchSample", riskMismatch ? 1 : 0);
        return performance;
    }

    private static Map<String, Object> sample(ScoringMode mode, FraudScoreResult mlResult, double value) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("value", value);
        sample.put("labels", labels(mode, mlResult));
        return sample;
    }

    private static Map<String, Object> bucketSample(ScoringMode mode, FraudScoreResult mlResult, double score) {
        Map<String, Object> sample = sample(mode, mlResult, 1);
        sample.put("bucket", scoreBucket(score));
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

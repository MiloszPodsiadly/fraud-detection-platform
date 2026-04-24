package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.domain.MlModelInput;
import com.frauddetection.scoring.domain.MlModelOutput;
import com.frauddetection.scoring.observability.ScoringMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeFraudScoringEngineTest {

    @Test
    void shouldUseRuleBasedScoringByDefault() {
        CompositeFraudScoringEngine engine = engine(ScoringMode.RULE_BASED);
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.scoreDetails()).doesNotContainKey("mlDiagnostics");
    }

    @Test
    void shouldFallbackToRuleBasedWhenMlModeHasNoModelRuntime() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CompositeFraudScoringEngine engine = engine(ScoringMode.ML, new PlaceholderMlModelScoringClient(), new ScoringMetrics(meterRegistry));
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.scoreDetails()).containsKey("mlDiagnostics");
        assertThat(mlDiagnostics(result))
                .containsEntry("fallbackUsed", true)
                .containsEntry("mlModelName", "ml-placeholder");
        assertThat(meterRegistry.get("fraud.scoring.fallbacks")
                .tags("mode", "ml", "reason", "no_ml_model_runtime_is_configured_yet")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void shouldComputeShadowMlScoreWithoutChangingFinalDecision() {
        CompositeFraudScoringEngine engine = engine(ScoringMode.SHADOW);
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(mlDiagnostics(result))
                .containsEntry("mode", "SHADOW")
                .containsEntry("finalDecisionSource", "RULE_BASED")
                .containsEntry("shadowModelName", "ml-placeholder")
                .containsKey("modelMonitoring");
        assertThat(modelMonitoring(result))
                .containsEntry("mode", "SHADOW")
                .containsEntry("modelVersion", "unavailable")
                .containsEntry("finalDecisionSource", "RULE_BASED")
                .containsKey("prometheusSamples")
                .containsKey("modelPerformanceByVersion");
    }

    @Test
    void shouldNotReportShadowFallbackWhenMlModelIsAvailable() {
        CompositeFraudScoringEngine engine = engine(ScoringMode.SHADOW, input -> new MlModelOutput(
                true,
                0.42d,
                RiskLevel.LOW,
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                List.of("LOW_MODEL_RISK"),
                Map.of("modelAvailable", true),
                Map.of("modelAvailable", true),
                null
        ));
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(mlDiagnostics(result))
                .containsEntry("shadowModelAvailable", true)
                .containsEntry("shadowModelName", "python-logistic-fraud-model")
                .doesNotContainKey("shadowFallbackReason");
        assertThat(modelMonitoring(result))
                .containsEntry("modelAvailable", true)
                .containsEntry("mlScoreBucket", "0.25-0.50");
    }

    @Test
    void shouldAttachComparisonMetadataWithoutChangingFinalDecision() {
        CompositeFraudScoringEngine engine = engine(ScoringMode.COMPARE);
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(mlDiagnostics(result))
                .containsEntry("mode", "COMPARE")
                .containsEntry("finalDecisionSource", "RULE_BASED")
                .containsKey("scoreDelta")
                .containsKey("riskLevelMatch");
        assertThat(modelMonitoring(result))
                .containsEntry("mode", "COMPARE")
                .containsKey("scoreDelta")
                .containsKey("absoluteScoreDelta")
                .containsKey("decisionDisagreementSample")
                .containsKey("riskLevelMismatchSample");
    }

    @Test
    void shouldTrackRiskMismatchAndDisagreementForAvailableCompareModel() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CompositeFraudScoringEngine engine = engine(ScoringMode.COMPARE, input -> new MlModelOutput(
                true,
                0.95d,
                RiskLevel.CRITICAL,
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                List.of("MODEL_HIGH_RISK"),
                Map.of("modelAvailable", true),
                Map.of("modelAvailable", true),
                null
        ), new ScoringMetrics(meterRegistry));
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(modelMonitoring(result))
                .containsEntry("modelVersion", "test-version")
                .containsEntry("riskLevelMismatch", true)
                .containsEntry("riskLevelMismatchSample", 1)
                .containsEntry("decisionDisagreementSample", 1);
        assertThat(meterRegistry.get("fraud.scoring.ml.diagnostics.disagreements")
                .tags("mode", "compare", "signal", "decision")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("fraud.scoring.ml.diagnostics.disagreements")
                .tags("mode", "compare", "signal", "risk_level")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    private CompositeFraudScoringEngine engine(ScoringMode mode) {
        return engine(mode, new PlaceholderMlModelScoringClient(), new ScoringMetrics(new SimpleMeterRegistry()));
    }

    private CompositeFraudScoringEngine engine(ScoringMode mode, MlModelScoringClient mlModelScoringClient) {
        return engine(mode, mlModelScoringClient, new ScoringMetrics(new SimpleMeterRegistry()));
    }

    private CompositeFraudScoringEngine engine(ScoringMode mode, MlModelScoringClient mlModelScoringClient, ScoringMetrics scoringMetrics) {
        ScoringProperties properties = new ScoringProperties(0.75d, 0.90d, mode);
        return new CompositeFraudScoringEngine(
                new RuleBasedFraudScoringEngine(properties),
                new MlFraudScoringEngine(mlModelScoringClient),
                properties,
                scoringMetrics
        );
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> mlDiagnostics(com.frauddetection.scoring.domain.FraudScoreResult result) {
        return (java.util.Map<String, Object>) result.scoreDetails().get("mlDiagnostics");
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> modelMonitoring(com.frauddetection.scoring.domain.FraudScoreResult result) {
        return (java.util.Map<String, Object>) mlDiagnostics(result).get("modelMonitoring");
    }
}

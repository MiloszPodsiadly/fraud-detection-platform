package com.frauddetection.scoring.service;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.junit.jupiter.api.Test;

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
        CompositeFraudScoringEngine engine = engine(ScoringMode.ML);
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(result.modelName()).isEqualTo("rule-based-engine");
        assertThat(result.scoreDetails()).containsKey("mlDiagnostics");
        assertThat(mlDiagnostics(result))
                .containsEntry("fallbackUsed", true)
                .containsEntry("mlModelName", "ml-placeholder");
    }

    @Test
    void shouldComputeShadowMlScoreWithoutChangingFinalDecision() {
        CompositeFraudScoringEngine engine = engine(ScoringMode.SHADOW);
        FraudScoringRequest request = FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build());

        var result = engine.score(request);

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(mlDiagnostics(result))
                .containsEntry("mode", "SHADOW")
                .containsEntry("finalDecisionSource", "RULE_BASED")
                .containsEntry("shadowModelName", "ml-placeholder");
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
    }

    private CompositeFraudScoringEngine engine(ScoringMode mode) {
        ScoringProperties properties = new ScoringProperties(0.75d, 0.90d, mode);
        return new CompositeFraudScoringEngine(
                new RuleBasedFraudScoringEngine(properties),
                new MlFraudScoringEngine(new PlaceholderMlModelScoringClient()),
                properties
        );
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> mlDiagnostics(com.frauddetection.scoring.domain.FraudScoreResult result) {
        return (java.util.Map<String, Object>) result.scoreDetails().get("mlDiagnostics");
    }
}

package com.frauddetection.scoring.service;

import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.scoring.config.ScoringMode;
import com.frauddetection.scoring.config.ScoringProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.observability.ScoringMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MlFallbackScoringEvidenceTest {

    @Test
    void mlUnavailableCreatesRuntimeEvidenceNotAvailableMlModelEvidence() {
        MlFraudScoringEngine engine = new MlFraudScoringEngine(
                new PlaceholderMlModelScoringClient(),
                new ScoringMetrics(new SimpleMeterRegistry())
        );

        var result = engine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.reasonCodes()).containsExactly(ReasonCode.ML_MODEL_UNAVAILABLE.wireValue());
        assertThat(result.scoringEvidence()).singleElement().satisfies(item -> {
            assertThat(item.reasonCode()).isEqualTo(ReasonCode.ML_MODEL_UNAVAILABLE.wireValue());
            assertThat(item.source()).isEqualTo(ScoringEvidenceSource.ML_RUNTIME);
            assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.UNAVAILABLE);
        });
        assertThat(result.scoringEvidence()).noneMatch(item ->
                item.source() == ScoringEvidenceSource.ML_MODEL && item.status() == ScoringEvidenceStatus.AVAILABLE);
        assertThat(result.alertRecommended()).isFalse();
    }

    @Test
    void mlModeUnavailableAddsScoringFallbackDiagnosticAndPreservesRuntimeEvidence() {
        CompositeFraudScoringEngine engine = engine(ScoringMode.ML);

        var result = engine.score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.scoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.scoringEvidence())
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(ScoringEvidenceSource.SCORING_FALLBACK);
                    assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.LEGACY);
                    assertThat(item.reasonCode()).isNull();
                    assertThat(item.attributes()).containsEntry("scoringEvidenceState", "ml_decision_fallback_used");
                })
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(ScoringEvidenceSource.ML_RUNTIME);
                    assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.UNAVAILABLE);
                    assertThat(item.reasonCode()).isEqualTo(ReasonCode.ML_MODEL_UNAVAILABLE.wireValue());
                })
                .anySatisfy(item -> {
                    assertThat(item.source()).isEqualTo(ScoringEvidenceSource.RULE_BASED_SCORING);
                    assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.AVAILABLE);
                });
        assertThat(result.scoringEvidence()).noneMatch(item ->
                item.source() == ScoringEvidenceSource.ML_MODEL && item.status() == ScoringEvidenceStatus.AVAILABLE);
    }

    @Test
    void shadowModeMlUnavailableDoesNotEmitScoringFallbackButEmitsMlRuntimeDiagnostic() {
        var result = engine(ScoringMode.SHADOW)
                .score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.scoringEvidence()).noneMatch(item -> item.source() == ScoringEvidenceSource.SCORING_FALLBACK);
        assertThat(result.scoringEvidence()).anySatisfy(item -> {
            assertThat(item.source()).isEqualTo(ScoringEvidenceSource.ML_RUNTIME);
            assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.UNAVAILABLE);
        });
    }

    @Test
    void compareModeMlUnavailableDoesNotEmitScoringFallbackButEmitsMlRuntimeDiagnostic() {
        var result = engine(ScoringMode.COMPARE)
                .score(FraudScoringRequest.from(TransactionFixtures.enrichedTransaction().build()));

        assertThat(result.scoringEvidence()).noneMatch(item -> item.source() == ScoringEvidenceSource.SCORING_FALLBACK);
        assertThat(result.scoringEvidence()).anySatisfy(item -> {
            assertThat(item.source()).isEqualTo(ScoringEvidenceSource.ML_RUNTIME);
            assertThat(item.status()).isEqualTo(ScoringEvidenceStatus.UNAVAILABLE);
        });
    }

    private CompositeFraudScoringEngine engine(ScoringMode mode) {
        ScoringProperties properties = new ScoringProperties(0.75d, 0.90d, mode);
        ScoringMetrics scoringMetrics = new ScoringMetrics(new SimpleMeterRegistry());
        return new CompositeFraudScoringEngine(
                new RuleBasedFraudScoringEngine(properties),
                new MlFraudScoringEngine(new PlaceholderMlModelScoringClient(), scoringMetrics),
                properties,
                scoringMetrics
        );
    }
}

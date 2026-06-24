package com.frauddetection.scoring.service;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparison;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;
import com.frauddetection.common.events.recommendation.AnalystRecommendation;
import com.frauddetection.common.events.recommendation.AnalystRecommendationConfidence;
import com.frauddetection.common.events.recommendation.AnalystRecommendationSource;
import com.frauddetection.common.events.recommendation.AnalystRecommendationStatus;
import com.frauddetection.scoring.domain.FraudScoreResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AnalystRecommendationServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-19T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private final AnalystRecommendationService service = new AnalystRecommendationService(FIXED_CLOCK);

    @Test
    void absentEngineIntelligenceMapsToAbsentWithoutNoActionRecommendation() {
        var result = service.recommend(scoreResult(RiskLevel.CRITICAL), Optional.empty());

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.ABSENT);
        assertThat(result.recommendation()).isNull();
        assertThat(result.source()).isEqualTo(AnalystRecommendationSource.ENGINE_INTELLIGENCE_ABSENT);
        assertThat(result.recommendationVersion()).isEqualTo("analyst-recommendation-v1");
        assertThat(result.generatedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void rulesHighRiskProducesAdvisoryReviewRecommendation() {
        var result = service.recommend(scoreResult(RiskLevel.HIGH), Optional.of(summary(
                rules(RiskLevel.HIGH, "HIGH_VELOCITY")
        )));

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.AVAILABLE);
        assertThat(result.recommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_REVIEW);
        assertThat(result.confidence()).isEqualTo(AnalystRecommendationConfidence.LOW);
        assertThat(result.source()).isEqualTo(AnalystRecommendationSource.RULES_RISK);
        assertThat(result.recommendationVersion()).isEqualTo("analyst-recommendation-v1");
        assertThat(result.generatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(result.reasonCodes()).containsExactly("RULES_HIGH_RISK");
        assertThat(result.nonDecisioning().notPaymentAuthorization()).isTrue();
        assertThat(result.nonDecisioning().notAutomaticDecisioning()).isTrue();
        assertThat(result.nonDecisioning().notCaseAction()).isTrue();
    }

    @Test
    void rapidTransferHighRiskProducesAdvisoryCaseCreationRecommendationOnly() {
        var result = service.recommend(scoreResult(RiskLevel.CRITICAL), Optional.of(summary(
                rules(RiskLevel.CRITICAL, "RAPID_TRANSFER_FRAUD_CASE")
        )));

        assertThat(result.recommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_CASE_CREATION);
        assertThat(result.reasonCodes()).containsExactly("RAPID_TRANSFER_PATTERN", "RULES_HIGH_RISK");
        assertThat(result.nonDecisioning().notCaseAction()).isTrue();
        assertThat(result.nonDecisioning().notWorkflowAction()).isTrue();
    }

    @Test
    void mlHighRiskWithRulesLowerRiskProducesRiskMismatchReview() {
        var result = service.recommend(scoreResult(RiskLevel.MEDIUM), Optional.of(summary(
                rules(RiskLevel.MEDIUM, "LOW_MODEL_RISK"),
                ml(RiskLevel.HIGH, "MODEL_HIGH_RISK")
        )));

        assertThat(result.recommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_REVIEW);
        assertThat(result.confidence()).isEqualTo(AnalystRecommendationConfidence.LOW);
        assertThat(result.source()).isEqualTo(AnalystRecommendationSource.RISK_MISMATCH);
        assertThat(result.reasonCodes()).containsExactly("ML_HIGH_RISK_RULES_LOWER_RISK", "ENGINE_RISK_DISAGREEMENT");
    }

    @Test
    void bothLowRiskProducesNoActionOnlyWhenEngineIntelligenceIsPresent() {
        var result = service.recommend(scoreResult(RiskLevel.LOW), Optional.of(summary(
                rules(RiskLevel.LOW, "LOW_MODEL_RISK"),
                ml(RiskLevel.LOW, "LOW_MODEL_RISK")
        )));

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.AVAILABLE);
        assertThat(result.recommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_NO_ACTION);
        assertThat(result.reasonCodes()).containsExactly("BOTH_ENGINES_LOW_RISK", "LOW_RISK_DIAGNOSTIC_CONTEXT");
    }

    @Test
    void recommendNoActionKeepsNonDecisioningBoundary() {
        var result = service.recommend(scoreResult(RiskLevel.LOW), Optional.of(summary(
                rules(RiskLevel.LOW, "LOW_MODEL_RISK"),
                ml(RiskLevel.LOW, "LOW_MODEL_RISK")
        )));

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.AVAILABLE);
        assertThat(result.recommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_NO_ACTION);
        assertThat(result.reasonCodes()).containsAnyOf("BOTH_ENGINES_LOW_RISK", "LOW_RISK_DIAGNOSTIC_CONTEXT");
        assertThat(result.nonDecisioning().notPaymentAuthorization()).isTrue();
        assertThat(result.nonDecisioning().notAutomaticDecisioning()).isTrue();
        assertThat(result.nonDecisioning().notCaseAction()).isTrue();
        assertThat(result.nonDecisioning().notWorkflowAction()).isTrue();
        assertThat(result.nonDecisioning().notModelPromotion()).isTrue();
        assertThat(result.nonDecisioning().notThresholdRecommendation()).isTrue();
    }

    @Test
    void degradedEngineIntelligenceKeepsVisibleDegradedStatusWithoutEscalatingSeverity() {
        var result = service.recommend(scoreResult(RiskLevel.HIGH), Optional.of(summary(
                List.of(new EngineIntelligenceWarningSummary(EngineIntelligenceWarningCode.REASON_CODE_LIMIT_APPLIED, 1)),
                rules(RiskLevel.HIGH, "HIGH_VELOCITY")
        )));

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.DEGRADED);
        assertThat(result.source()).isEqualTo(AnalystRecommendationSource.ENGINE_INTELLIGENCE_DEGRADED);
        assertThat(result.recommendation()).isEqualTo(AnalystRecommendation.RECOMMEND_REVIEW);
        assertThat(result.recommendationVersion()).isEqualTo("analyst-recommendation-v1");
        assertThat(result.generatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(result.warnings()).singleElement()
                .satisfies(warning -> assertThat(warning.warningCode()).isEqualTo("ENGINE_INTELLIGENCE_DEGRADED"));
    }

    @Test
    void unavailableMlEngineAloneDoesNotIncreaseRecommendationSeverity() {
        var result = service.recommend(scoreResult(RiskLevel.LOW), Optional.of(summary(
                new EngineIntelligenceEngineResult(
                        "ml.python.primary",
                        FraudEngineType.ML_MODEL,
                        FraudEngineStatus.UNAVAILABLE,
                        null,
                        EngineIntelligenceScoreBucket.UNAVAILABLE,
                        List.of("ML_MODEL_UNAVAILABLE")
                )
        )));

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.INSUFFICIENT_DATA);
        assertThat(result.recommendation()).isNull();
    }

    @Test
    void unavailableFactoryProducesUnavailableWithoutRecommendation() {
        var result = service.unavailable();

        assertThat(result.status()).isEqualTo(AnalystRecommendationStatus.UNAVAILABLE);
        assertThat(result.recommendation()).isNull();
        assertThat(result.source()).isEqualTo(AnalystRecommendationSource.ENGINE_INTELLIGENCE_UNAVAILABLE);
        assertThat(result.recommendationVersion()).isEqualTo("analyst-recommendation-v1");
        assertThat(result.generatedAt()).isEqualTo(FIXED_INSTANT);
    }

    private FraudScoreResult scoreResult(RiskLevel riskLevel) {
        return new FraudScoreResult(
                0.5d,
                riskLevel,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.parse("2026-06-19T10:00:00Z"),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL
        );
    }

    private EngineIntelligenceSummary summary(EngineIntelligenceEngineResult... engines) {
        return summary(List.of(), engines);
    }

    private EngineIntelligenceSummary summary(
            List<EngineIntelligenceWarningSummary> warnings,
            EngineIntelligenceEngineResult... engines
    ) {
        return new EngineIntelligenceSummary(
                EngineIntelligenceSummary.CONTRACT_VERSION,
                Instant.parse("2026-06-19T10:00:01Z"),
                List.of(engines),
                new EngineIntelligenceComparison(
                        EngineIntelligenceAgreementStatus.PARTIAL,
                        EngineIntelligenceRiskMismatchStatus.NOT_COMPARABLE,
                        EngineIntelligenceScoreDeltaBucket.UNAVAILABLE
                ),
                List.of(),
                warnings
        );
    }

    private EngineIntelligenceEngineResult rules(RiskLevel riskLevel, String reasonCode) {
        return new EngineIntelligenceEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                riskLevel,
                EngineIntelligenceScoreBucket.HIGH,
                List.of(reasonCode)
        );
    }

    private EngineIntelligenceEngineResult ml(RiskLevel riskLevel, String reasonCode) {
        return new EngineIntelligenceEngineResult(
                "ml.python.primary",
                FraudEngineType.ML_MODEL,
                FraudEngineStatus.AVAILABLE,
                riskLevel,
                EngineIntelligenceScoreBucket.HIGH,
                List.of(reasonCode)
        );
    }
}

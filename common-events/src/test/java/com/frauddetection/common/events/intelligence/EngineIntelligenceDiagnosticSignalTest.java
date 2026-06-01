package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceDiagnosticSignalTest {

    @Test
    void diagnosticSignalUsesBoundedFieldsOnly() {
        assertThat(fields()).containsExactly(
                "engineId", "engineType", "engineStatus", "signalCategory", "riskLevel", "scoreBucket", "reasonCode"
        );
    }

    @Test
    void diagnosticSignalDoesNotExposeRawScore() {
        assertThat(fields()).doesNotContain("score", "rawScore");
    }

    @Test
    void diagnosticSignalDoesNotExposeEvidenceText() {
        assertThat(fields()).doesNotContain("evidence", "evidenceTitle", "evidenceDescription", "description");
    }

    @Test
    void diagnosticSignalDoesNotExposeContributionValue() {
        assertThat(fields()).doesNotContain("contribution", "contributionValue", "rawValue");
    }

    @Test
    void diagnosticSignalDoesNotContainRecommendedAction() {
        assertThat(fields()).doesNotContain("recommendedAction", "recommendation");
    }

    @Test
    void diagnosticSignalDoesNotContainFinalExplanation() {
        assertThat(fields()).doesNotContain("finalExplanation", "paymentDecisionRationale");
    }

    @Test
    void diagnosticSignalSerializesWithStableFieldNames() throws Exception {
        assertThat(EngineIntelligenceTestSupport.objectMapper().writeValueAsString(EngineIntelligenceTestSupport.signal()))
                .contains(
                        "\"engineId\":\"rules.primary\"",
                        "\"engineType\":\"RULES\"",
                        "\"engineStatus\":\"AVAILABLE\"",
                        "\"signalCategory\":\"FRAUD_SIGNAL\"",
                        "\"riskLevel\":\"HIGH\"",
                        "\"scoreBucket\":\"HIGH\"",
                        "\"reasonCode\":\"HIGH_VELOCITY\""
                );
    }

    @Test
    void rejectsTimeoutDiagnosticSignalWithRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.TIMEOUT, RiskLevel.LOW);
    }

    @Test
    void rejectsUnavailableDiagnosticSignalWithRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.UNAVAILABLE, RiskLevel.LOW);
    }

    @Test
    void rejectsDegradedDiagnosticSignalWithRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.DEGRADED, RiskLevel.LOW);
    }

    @Test
    void acceptsTimeoutDiagnosticSignalWithNullRiskAndUnavailableScoreBucket() {
        assertThat(signal(
                FraudEngineStatus.TIMEOUT,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE
        ).riskLevel()).isNull();
    }

    @Test
    void acceptsAvailableFraudSignalWithRiskLevel() {
        assertThat(signal(
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH
        ).riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void errorMessageIsBounded() {
        assertThatThrownBy(() -> signal(
                FraudEngineStatus.TIMEOUT,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                RiskLevel.LOW,
                EngineIntelligenceScoreBucket.UNAVAILABLE
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_RISK_LEVEL_INVALID")
                .message()
                .doesNotContain("TIMEOUT", "LOW");
    }

    @Test
    void rejectsOperationalSignalWithHighScoreBucket() {
        assertOperationalScoreBucketRejected(EngineIntelligenceScoreBucket.HIGH);
    }

    @Test
    void rejectsOperationalSignalWithVeryHighScoreBucket() {
        assertOperationalScoreBucketRejected(EngineIntelligenceScoreBucket.VERY_HIGH);
    }

    @Test
    void acceptsOperationalSignalWithUnavailableScoreBucket() {
        assertThat(signal(
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                null,
                EngineIntelligenceScoreBucket.UNAVAILABLE
        ).scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.UNAVAILABLE);
    }

    @Test
    void acceptsFraudSignalWithHighScoreBucketWhenAvailable() {
        assertThat(signal(
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.FRAUD_SIGNAL,
                RiskLevel.HIGH,
                EngineIntelligenceScoreBucket.HIGH
        ).scoreBucket()).isEqualTo(EngineIntelligenceScoreBucket.HIGH);
    }

    private void assertOperationalRiskRejected(FraudEngineStatus status, RiskLevel riskLevel) {
        assertThatThrownBy(() -> signal(
                status,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                riskLevel,
                EngineIntelligenceScoreBucket.UNAVAILABLE
        )).hasMessage("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_RISK_LEVEL_INVALID");
    }

    private void assertOperationalScoreBucketRejected(EngineIntelligenceScoreBucket scoreBucket) {
        assertThatThrownBy(() -> signal(
                FraudEngineStatus.AVAILABLE,
                EngineIntelligenceSignalCategory.OPERATIONAL_SIGNAL,
                null,
                scoreBucket
        )).hasMessage("ENGINE_INTELLIGENCE_OPERATIONAL_SIGNAL_SCORE_BUCKET_INVALID");
    }

    private EngineIntelligenceDiagnosticSignal signal(
            FraudEngineStatus status,
            EngineIntelligenceSignalCategory signalCategory,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket
    ) {
        return new EngineIntelligenceDiagnosticSignal(
                "rules.primary",
                FraudEngineType.RULES,
                status,
                signalCategory,
                riskLevel,
                scoreBucket,
                "HIGH_VELOCITY"
        );
    }

    private java.util.List<String> fields() {
        return Arrays.stream(EngineIntelligenceDiagnosticSignal.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}

package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineIntelligenceEngineResultTest {

    @Test
    void rejectsTimeoutEngineResultWithRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.TIMEOUT, RiskLevel.LOW);
    }

    @Test
    void rejectsUnavailableEngineResultWithRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.UNAVAILABLE, RiskLevel.LOW);
    }

    @Test
    void rejectsDegradedEngineResultWithRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.DEGRADED, RiskLevel.LOW);
    }

    @Test
    void acceptsTimeoutEngineResultWithNullRiskAndUnavailableScoreBucket() {
        assertThat(engine(FraudEngineStatus.TIMEOUT, null, EngineIntelligenceScoreBucket.UNAVAILABLE).riskLevel()).isNull();
    }

    @Test
    void acceptsAvailableEngineResultWithRiskLevel() {
        assertThat(engine(FraudEngineStatus.AVAILABLE, RiskLevel.HIGH, EngineIntelligenceScoreBucket.HIGH).riskLevel())
                .isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void rejectsOperationalStatusWithHighRiskLevel() {
        assertOperationalRiskRejected(FraudEngineStatus.SKIPPED, RiskLevel.HIGH);
    }

    @Test
    void errorMessageIsBounded() {
        assertThatThrownBy(() -> engine(FraudEngineStatus.TIMEOUT, RiskLevel.LOW, EngineIntelligenceScoreBucket.UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ENGINE_INTELLIGENCE_OPERATIONAL_STATUS_RISK_LEVEL_INVALID")
                .message()
                .doesNotContain("TIMEOUT", "LOW");
    }

    private void assertOperationalRiskRejected(FraudEngineStatus status, RiskLevel riskLevel) {
        assertThatThrownBy(() -> engine(status, riskLevel, EngineIntelligenceScoreBucket.UNAVAILABLE))
                .hasMessage("ENGINE_INTELLIGENCE_OPERATIONAL_STATUS_RISK_LEVEL_INVALID");
    }

    private EngineIntelligenceEngineResult engine(
            FraudEngineStatus status,
            RiskLevel riskLevel,
            EngineIntelligenceScoreBucket scoreBucket
    ) {
        return new EngineIntelligenceEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                status,
                riskLevel,
                scoreBucket,
                List.of("HIGH_VELOCITY")
        );
    }
}

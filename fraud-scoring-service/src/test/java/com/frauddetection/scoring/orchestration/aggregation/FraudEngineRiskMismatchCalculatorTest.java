package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineRiskMismatchCalculatorTest {
    private final FraudEngineRiskMismatchCalculator calculator = new FraudEngineRiskMismatchCalculator();

    @Test
    void classifiesSameAdjacentAndMaterialRiskLevels() {
        assertThat(calculate(RiskLevel.HIGH, RiskLevel.HIGH)).isEqualTo(FraudEngineRiskMismatchStatus.SAME_RISK_LEVEL);
        assertThat(calculate(RiskLevel.LOW, RiskLevel.LOW)).isEqualTo(FraudEngineRiskMismatchStatus.SAME_RISK_LEVEL);
        assertThat(calculate(RiskLevel.LOW, RiskLevel.MEDIUM)).isEqualTo(FraudEngineRiskMismatchStatus.ADJACENT_RISK_LEVEL);
        assertThat(calculate(RiskLevel.LOW, RiskLevel.HIGH)).isEqualTo(FraudEngineRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
        assertThat(calculate(RiskLevel.HIGH, RiskLevel.LOW)).isEqualTo(FraudEngineRiskMismatchStatus.MATERIAL_RISK_MISMATCH);
    }

    @Test
    void operationalOrMissingRiskIsNotComparableAndDoesNotBecomeLow() {
        for (FraudEngineStatus status : List.of(FraudEngineStatus.TIMEOUT, FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.DEGRADED)) {
            assertThat(calculator.calculate(List.of(
                    result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH),
                    result("ml.python.primary", status, null)
            )).status()).isEqualTo(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
        }
        assertThat(calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, null)
        )).toString()).doesNotContain("LOW");
    }

    @Test
    void reversedEngineOrderProducesSameRiskMismatch() {
        FraudEngineRiskMismatch ordered = calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.LOW),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH)
        ));
        FraudEngineRiskMismatch reversed = calculator.calculate(List.of(
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH),
                result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.LOW)
        ));

        assertThat(reversed).isEqualTo(ordered);
    }

    @Test
    void missingNamedEngineProducesNotComparable() {
        assertThat(calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH)
        )).status()).isEqualTo(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
        assertThat(calculator.calculate(List.of(
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH)
        )).status()).isEqualTo(FraudEngineRiskMismatchStatus.NOT_COMPARABLE);
    }

    private FraudEngineRiskMismatchStatus calculate(RiskLevel rules, RiskLevel ml) {
        return calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, rules),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, ml)
        )).status();
    }

    private NormalizedFraudEngineResult result(String engineId, FraudEngineStatus status, RiskLevel risk) {
        return AggregationTestSupport.normalized(engineId, status, risk == null ? null : 0.5d, risk, "MODEL_HIGH_RISK");
    }
}

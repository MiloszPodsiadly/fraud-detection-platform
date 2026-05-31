package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineScoreDeltaCalculatorTest {
    private final FraudEngineScoreDeltaCalculator calculator = new FraudEngineScoreDeltaCalculator();

    @Test
    void computesAbsoluteDeltaOnlyForComparableScores() {
        FraudEngineScoreDelta delta = calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.9d),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, 0.4d)
        ));

        assertThat(delta.status()).isEqualTo(FraudEngineScoreDeltaStatus.AVAILABLE);
        assertThat(delta.absoluteDelta()).isEqualTo(0.5d);
        assertThat(calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.4d),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, 0.4d)
        )).absoluteDelta()).isZero();
    }

    @Test
    void missingOrOperationalScoreNeverDefaultsToZero() {
        assertThat(calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.4d),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, null)
        ))).isEqualTo(new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_MISSING_SCORE, null));
        for (FraudEngineStatus status : List.of(FraudEngineStatus.TIMEOUT, FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.DEGRADED)) {
            assertThat(calculator.calculate(List.of(
                    result("rules.primary", FraudEngineStatus.AVAILABLE, 0.4d),
                    result("ml.python.primary", status, null)
            ))).isEqualTo(new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_ENGINE_STATUS, null));
        }
    }

    @Test
    void exposesNoFinalDecisionOrRiskFields() {
        assertThat(Arrays.stream(FraudEngineScoreDelta.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("finalScore", "finalRisk", "finalDecision", "recommendedAction");
    }

    @Test
    void reversedEngineOrderProducesSameScoreDelta() {
        FraudEngineScoreDelta ordered = calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.9d),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, 0.4d)
        ));
        FraudEngineScoreDelta reversed = calculator.calculate(List.of(
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, 0.4d),
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.9d)
        ));

        assertThat(reversed).isEqualTo(ordered);
    }

    @Test
    void missingNamedEngineProducesUnavailable() {
        assertThat(calculator.calculate(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.9d)
        ))).isEqualTo(new FraudEngineScoreDelta(
                FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS,
                null
        ));
        assertThat(calculator.calculate(List.of(
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, 0.4d)
        ))).isEqualTo(new FraudEngineScoreDelta(
                FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS,
                null
        ));
    }

    private NormalizedFraudEngineResult result(String engineId, FraudEngineStatus status, Double score) {
        return AggregationTestSupport.normalized(engineId, status, score, score == null ? null : RiskLevel.HIGH, "MODEL_HIGH_RISK");
    }
}

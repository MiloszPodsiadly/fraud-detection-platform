package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineConfidence;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedFraudEngineResultTest {

    @Test
    void defensivelyCopiesLists() {
        List<String> reasons = new ArrayList<>(List.of("MODEL_HIGH_RISK"));
        NormalizedFraudEngineResult result = result("rules.primary", FraudEngineStatus.AVAILABLE, 0.8d, reasons);

        reasons.clear();

        assertThat(result.reasonCodes()).containsExactly("MODEL_HIGH_RISK");
        assertThatThrownBy(() -> result.reasonCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesAllowlistedEngineId() {
        assertThatThrownBy(() -> result(null, FraudEngineStatus.TIMEOUT, null, List.of()))
                .hasMessage("AGGREGATION_ENGINE_ID_REQUIRED");
        assertThatThrownBy(() -> result(" ", FraudEngineStatus.TIMEOUT, null, List.of()))
                .hasMessage("AGGREGATION_ENGINE_ID_REQUIRED");
        assertThatThrownBy(() -> result("unknown.primary", FraudEngineStatus.TIMEOUT, null, List.of()))
                .hasMessage("AGGREGATION_UNKNOWN_ENGINE_ID");
        assertThat(result("rules.primary", FraudEngineStatus.TIMEOUT, null, List.of()).engineId()).isEqualTo("rules.primary");
        assertThat(result("ml.python.primary", FraudEngineStatus.TIMEOUT, null, List.of()).engineId()).isEqualTo("ml.python.primary");
    }

    @Test
    void rejectsInvalidScoreRange() {
        assertThatThrownBy(() -> result("rules.primary", FraudEngineStatus.AVAILABLE, 1.1d, List.of()))
                .hasMessage("AGGREGATION_SCORE_OUT_OF_RANGE");
    }

    @Test
    void allowsNullScoreForOperationalStatuses() {
        assertThat(result("rules.primary", FraudEngineStatus.TIMEOUT, null, List.of()).score()).isNull();
        assertThat(result("rules.primary", FraudEngineStatus.UNAVAILABLE, null, List.of()).score()).isNull();
        assertThat(result("rules.primary", FraudEngineStatus.DEGRADED, null, List.of()).score()).isNull();
    }

    @Test
    void rejectsUnnormalizedReasonCodes() {
        assertThatThrownBy(() -> result(
                "rules.primary",
                FraudEngineStatus.AVAILABLE,
                0.8d,
                List.of("raw-token-endpoint-accountId")
        )).hasMessage("AGGREGATION_UNNORMALIZED_REASON_CODE");
    }

    private NormalizedFraudEngineResult result(
            String engineId,
            FraudEngineStatus status,
            Double score,
            List<String> reasons
    ) {
        return new NormalizedFraudEngineResult(
                engineId,
                FraudEngineType.RULES,
                status,
                score,
                score == null ? null : com.frauddetection.common.events.enums.RiskLevel.HIGH,
                score == null ? FraudEngineConfidence.UNKNOWN : FraudEngineConfidence.MEDIUM,
                reasons,
                List.of(),
                List.of(),
                0L
        );
    }
}

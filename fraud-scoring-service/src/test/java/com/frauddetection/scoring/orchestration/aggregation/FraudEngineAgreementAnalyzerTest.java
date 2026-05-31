package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineAgreementAnalyzerTest {
    private final FraudEngineAgreementAnalyzer analyzer = new FraudEngineAgreementAnalyzer();

    @Test
    void availableAlignedResultsAgreeAndMaterialMismatchDisagrees() {
        assertThat(analyze(RiskLevel.HIGH, RiskLevel.HIGH)).isEqualTo(FraudEngineAgreementStatus.AGREEMENT);
        assertThat(analyze(RiskLevel.LOW, RiskLevel.LOW)).isEqualTo(FraudEngineAgreementStatus.AGREEMENT);
        assertThat(analyze(RiskLevel.HIGH, RiskLevel.LOW)).isEqualTo(FraudEngineAgreementStatus.DISAGREEMENT);
        assertThat(analyze(RiskLevel.LOW, RiskLevel.HIGH)).isEqualTo(FraudEngineAgreementStatus.DISAGREEMENT);
    }

    @Test
    void optionalOperationalFailureIsPartial() {
        for (FraudEngineStatus status : List.of(FraudEngineStatus.TIMEOUT, FraudEngineStatus.UNAVAILABLE, FraudEngineStatus.DEGRADED)) {
            assertThat(analyzer.analyze(List.of(
                    result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH),
                    result("ml.python.primary", status, null)
            ))).isEqualTo(FraudEngineAgreementStatus.PARTIAL);
        }
    }

    @Test
    void requiredFailureAndOneEngineAreNotComparable() {
        assertThat(analyzer.analyze(List.of(
                result("rules.primary", FraudEngineStatus.TIMEOUT, null),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH)
        ))).isEqualTo(FraudEngineAgreementStatus.REQUIRED_ENGINE_NOT_COMPARABLE);
        assertThat(analyzer.analyze(List.of(result("rules.primary", FraudEngineStatus.AVAILABLE, RiskLevel.HIGH))))
                .isEqualTo(FraudEngineAgreementStatus.INSUFFICIENT_DATA);
    }

    @Test
    void analyzerExposesNoDecisionMethods() {
        assertThat(Arrays.stream(FraudEngineAgreementAnalyzer.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("approve", "decline", "block", "decide", "recommend");
    }

    private FraudEngineAgreementStatus analyze(RiskLevel rules, RiskLevel ml) {
        return analyzer.analyze(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, rules),
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, ml)
        ));
    }

    private NormalizedFraudEngineResult result(String engineId, FraudEngineStatus status, RiskLevel risk) {
        return AggregationTestSupport.normalized(engineId, status, risk == null ? null : 0.5d, risk, "MODEL_HIGH_RISK");
    }
}

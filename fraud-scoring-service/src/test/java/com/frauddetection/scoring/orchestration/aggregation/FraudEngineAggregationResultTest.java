package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineAggregationResultTest {

    @Test
    void defensivelyCopiesLists() {
        List<NormalizedFraudEngineResult> normalized = new ArrayList<>(List.of(
                AggregationTestSupport.normalized("rules.primary", FraudEngineStatus.AVAILABLE, 0.8d, RiskLevel.HIGH, "MODEL_HIGH_RISK")
        ));
        FraudEngineAggregationResult result = new FraudEngineAggregationResult(
                normalized,
                FraudEngineAgreementStatus.INSUFFICIENT_DATA,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS, null),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(),
                List.of(),
                AggregationTestSupport.GENERATED_AT
        );

        normalized.clear();

        assertThat(result.normalizedEngineResults()).hasSize(1);
        assertThatThrownBy(() -> result.normalizedEngineResults().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exposesNoFinalDecisionFieldsOrDtoAnnotations() {
        assertThat(Arrays.stream(FraudEngineAggregationResult.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain(
                        "finalScore",
                        "finalRisk",
                        "finalDecision",
                        "recommendedAction",
                        "platformRiskScore",
                        "platformRiskLevel",
                        "winningEngine"
                );
        assertThat(Arrays.stream(FraudEngineAggregationResult.class.getAnnotations()).map(Annotation::annotationType))
                .isEmpty();
    }

    @Test
    void rejectsOversizedListsWhenConstructedDirectly() {
        List<FraudEngineAggregationWarning> warnings = IntStream.range(0, 65)
                .mapToObj(index -> new FraudEngineAggregationWarning(
                        "rules.primary",
                        FraudEngineAggregationWarningCode.REASON_CODE_UNSUPPORTED_DROPPED
                ))
                .toList();

        assertThatThrownBy(() -> new FraudEngineAggregationResult(
                List.of(),
                FraudEngineAgreementStatus.INSUFFICIENT_DATA,
                new FraudEngineScoreDelta(FraudEngineScoreDeltaStatus.UNAVAILABLE_NOT_ENOUGH_COMPARABLE_RESULTS, null),
                new FraudEngineRiskMismatch(FraudEngineRiskMismatchStatus.NOT_COMPARABLE),
                List.of(),
                warnings,
                AggregationTestSupport.GENERATED_AT
        )).hasMessage("AGGREGATION_WARNINGS_LIMIT_EXCEEDED");
    }
}

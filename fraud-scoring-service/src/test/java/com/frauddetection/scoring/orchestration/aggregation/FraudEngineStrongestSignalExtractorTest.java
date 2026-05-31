package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineStrongestSignalExtractorTest {
    private final FraudEngineStrongestSignalExtractor extractor = new FraudEngineStrongestSignalExtractor();

    @Test
    void strongestSignalsAreCappedAndDeterministic() {
        FraudEngineAggregationPolicy strict = new FraudEngineAggregationPolicy(2, 10, 5, 5, 2, 20, 128, 120, 256);
        List<NormalizedFraudEngineResult> results = List.of(
                result("ml.python.primary", FraudEngineStatus.AVAILABLE, 0.7d, RiskLevel.HIGH, "MODEL_HIGH_RISK"),
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.9d, RiskLevel.CRITICAL, "HIGH_VELOCITY", "HIGH_TRANSACTION_AMOUNT")
        );

        List<FraudEngineStrongestSignal> first = extractor.extract(results, strict);
        List<FraudEngineStrongestSignal> second = extractor.extract(results, strict);

        assertThat(first).isEqualTo(second).hasSize(2);
        assertThat(first).extracting(FraudEngineStrongestSignal::reasonCode)
                .containsExactly("HIGH_TRANSACTION_AMOUNT", "HIGH_VELOCITY");
    }

    @Test
    void operationalFailuresProduceOperationalSignalsOnly() {
        List<FraudEngineStrongestSignal> signals = extractor.extract(List.of(
                result("rules.primary", FraudEngineStatus.AVAILABLE, 0.8d, RiskLevel.HIGH, "HIGH_VELOCITY"),
                result("ml.python.primary", FraudEngineStatus.TIMEOUT, null, null, "ORCHESTRATOR_ENGINE_TIMEOUT")
        ), FraudEngineAggregationPolicy.defaultInternalPolicy());

        assertThat(signals).filteredOn(signal -> signal.engineId().equals("ml.python.primary"))
                .extracting(FraudEngineStrongestSignal::signalCategory)
                .containsExactly(FraudEngineSignalCategory.OPERATIONAL_SIGNAL);
        assertThat(signals.toString()).doesNotContain("LOW");
    }

    @Test
    void strongestSignalsExposeNoRawEvidenceOrDecisionFields() {
        assertThat(Arrays.stream(FraudEngineStrongestSignal.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain(
                        "description",
                        "rawValue",
                        "payload",
                        "transactionId",
                        "customerId",
                        "accountId",
                        "recommendedAction",
                        "finalDecision"
                );
    }

    @Test
    void strongestSignalRejectsOutOfRangeScoreWhenConstructedDirectly() {
        assertThatThrownBy(() -> new FraudEngineStrongestSignal(
                "rules.primary",
                com.frauddetection.common.events.engine.FraudEngineType.RULES,
                FraudEngineStatus.AVAILABLE,
                RiskLevel.HIGH,
                1.1d,
                "HIGH_VELOCITY",
                null,
                FraudEngineSignalCategory.FRAUD_SIGNAL
        )).hasMessage("AGGREGATION_SIGNAL_SCORE_OUT_OF_RANGE");
    }

    private NormalizedFraudEngineResult result(
            String engineId,
            FraudEngineStatus status,
            Double score,
            RiskLevel risk,
            String... reasons
    ) {
        return AggregationTestSupport.normalized(engineId, status, score, risk, reasons);
    }
}
